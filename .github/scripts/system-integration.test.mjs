import assert from 'node:assert/strict';
import test from 'node:test';
import { runIntegration } from './system-integration.mjs';

test('WMS integration dispatches the PR commit and reports the remote result', async () => {
  const repository = 'twin10240/jhg-wms-project';
  const sha = 'a'.repeat(40);
  const pull = () => ({ number: 52, state: 'open', author_association: 'OWNER',
    head: { sha, repo: { full_name: repository } }, base: { repo: { full_name: repository } } });
  const statuses = [];
  let inputs;
  let reads = 0;
  const source = async (path, body) => body ? statuses.push({ path, ...body }) : pull();
  const remote = async (path, body) => {
    if (body) {
      inputs = body.inputs;
      assert.equal(body.ref, 'main');
      return { workflow_run_id: 42, html_url: 'https://github.com/twin10240/jhg-system-tests/actions/runs/42' };
    }
    const starting = ++reads === 1;
    return { id: 42, event: 'workflow_dispatch', head_branch: 'main',
      display_title: starting ? 'System integration' : `System integration · ${inputs.request_id} · wms`,
      status: starting ? 'queued' : 'completed', conclusion: 'success' };
  };
  const result = await runIntegration({ repository: { full_name: repository }, pull_request: pull() }, source, remote, async () => {});
  assert.equal(inputs.service, 'wms');
  assert.equal(inputs.sha, sha);
  assert.equal(inputs.source_repository, repository);
  assert.equal(result.state, 'success');
  assert.equal(statuses.at(-1).context, 'system-integration/wms');
  assert.equal(statuses.at(-1).state, 'success');
  assert.ok(statuses.every((status) => status.path === `statuses/${sha}`));
});
