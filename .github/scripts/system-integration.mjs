import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { appendFileSync, readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

const wms = 'twin10240/jhg-wms-project';
const system = 'twin10240/jhg-system-tests';

export async function runIntegration(event, sourceApi, systemApi, wait = delay, now = Date.now) {
  const pr = event.pull_request;
  const trusted = (pull) => {
    assert.equal(pull.base.repo.full_name, wms);
    assert.equal(pull.head.repo.full_name, wms, 'Fork PRs are not enabled');
    assert.ok(['OWNER', 'MEMBER', 'COLLABORATOR'].includes(pull.author_association), 'Untrusted PR author');
  };
  assert.equal(event.repository.full_name, wms);
  assert.ok(Number.isSafeInteger(pr.number) && pr.number > 0);
  trusted(pr);
  const sha = pr.head.sha;
  assert.match(sha, /^[0-9a-f]{40}$/);
  const current = await sourceApi(`pulls/${pr.number}`);
  trusted(current);
  if (current.state !== 'open' || current.head.sha !== sha) {
    console.log('PR is closed or has a newer head; skip this stale event');
    return { state: 'skipped' };
  }
  const status = (state, description, target_url) => sourceApi(`statuses/${sha}`, {
    state, context: 'system-integration/wms', description, ...(target_url ? { target_url } : {}),
  });
  await status('pending', 'Requesting isolated OMS/WMS/realtime test');
  const request_id = randomUUID();
  const dispatched = await systemApi('actions/workflows/system-test.yml/dispatches', {
    ref: 'main', return_run_details: true,
    inputs: { service: 'wms', sha, source_repository: wms, source_pr: String(pr.number), request_id },
  });
  const id = dispatched.workflow_run_id;
  assert.ok(Number.isSafeInteger(id) && id > 0, 'Dispatch did not return a run ID');
  const url = `https://github.com/${system}/actions/runs/${id}`;
  assert.equal(dispatched.html_url, url);
  console.log(JSON.stringify({ request_id, sha, run_id: id, url }));
  await status('pending', 'Isolated system test running', url);
  const deadline = now() + 25 * 60_000;
  let attempt = 0;
  while (now() < deadline) {
    const run = await systemApi(`actions/runs/${id}`);
    assert.equal(run.id, id);
    assert.equal(run.event, 'workflow_dispatch');
    assert.equal(run.head_branch, 'main');
    if (run.status === 'completed') {
      assert.equal(run.display_title, `System integration · ${request_id} · wms`, 'Run/request mismatch');
      const state = run.conclusion === 'success' ? 'success' : 'failure';
      await status(state, `System integration: ${run.conclusion}`, url);
      return { state, run_id: id, url, request_id, sha };
    }
    if (attempt++ % 6 === 0) console.log(`Waiting for system run ${id}: ${run.status}`);
    const remaining = deadline - now();
    if (remaining > 0) await wait(Math.min(10_000, remaining));
  }
  throw new Error(`System test wait exceeded 25 minutes: ${url}`);
}

if (import.meta.main) {
  const api = (repository, token) => {
    assert.ok(token, `Missing API token for ${repository}`);
    return async (path, body) => {
      const response = await fetch(`https://api.github.com/repos/${repository}/${path}`, {
        method: body ? 'POST' : 'GET', signal: AbortSignal.timeout(15_000),
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      assert.ok(response.ok, `GitHub ${path}: HTTP ${response.status}`);
      return response.status === 204 ? null : response.json();
    };
  };
  const result = await runIntegration(JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8')),
    api(wms, process.env.GITHUB_TOKEN), api(system, process.env.SYSTEM_TESTS_TOKEN));
  appendFileSync(process.env.GITHUB_OUTPUT, 'reported=true\n');
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## WMS system integration\n\n\`\`\`json\n${JSON.stringify(result, null, 2)}\n\`\`\`\n`);
  if (result.state === 'failure') process.exitCode = 1;
}
