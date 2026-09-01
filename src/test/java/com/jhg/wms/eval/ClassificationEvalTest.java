package com.jhg.wms.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudeReturnReasonClassifier;
import com.jhg.wms.service.ReturnReasonClassifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Claude API를 호출하는 품질 평가. 기본 test에서 제외돼 있다 — 실행은 ./gradlew evalTest.
 *
 * 점수로 실패하지 않는다. 임계값을 두면 비결정적 출력 때문에 언젠가 반드시 헛경보가 나고,
 * 지금 목적은 회귀 게이트가 아니라 측정이다. 실패 조건은 오직 "러너가 못 돌았다"이다.
 */
@Tag("eval")
class ClassificationEvalTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final int REPEATS = 3;
    private static final int PARALLELISM = 5;
    private static final Path REPORT = Path.of("build/reports/classification-eval.md");

    @Test
    void 분류_품질을_재고_리포트를_남긴다() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        // 키가 없으면 실패가 아니라 스킵이다. 이 테스트를 못 돌리는 것은 사고가 아니다.
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY 미설정 — 평가를 건너뜁니다.");

        ReturnReasonClassifier classifier = new ClaudeReturnReasonClassifier(
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .timeout(Duration.ofSeconds(20))
                        .maxRetries(1)
                        .build(),
                new ObjectMapper(), MODEL, 1024L);

        List<EvalCase> cases = EvalCase.loadAll();
        List<EvalAggregator.CaseResult> results = new ArrayList<>();
        String 실제모델 = MODEL;

        // 순차로 돌리면 90회 × 약 6초 = 9분이다. 동시 5면 2분 안쪽이고 rate limit에도 여유가 있다.
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            for (EvalCase c : cases) {
                List<Future<EvalObservation>> futures = new ArrayList<>();
                for (int i = 0; i < REPEATS; i++)
                    futures.add(pool.submit(observe(classifier, c)));

                List<EvalObservation> observations = new ArrayList<>();
                for (Future<EvalObservation> f : futures) observations.add(f.get());
                results.add(EvalAggregator.toCaseResult(c, observations));
            }
        } finally {
            pool.shutdown();
        }

        // 모델이 돌려준 확정 스냅샷을 쓴다. 별칭(claude-haiku-4-5)으로 적으면
        // 나중에 어느 버전에서 잰 점수인지 알 수 없다.
        for (EvalAggregator.CaseResult r : results)
            for (EvalObservation o : r.observations())
                if (o.succeeded() && o.model() != null) { 실제모델 = o.model(); break; }

        var summary = EvalAggregator.summarize(results);
        String report = EvalReportWriter.render(실제모델, REPEATS, results, summary);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        // 유일한 단언: 러너가 실제로 돌았는가. 점수는 판단하지 않는다.
        assertThat(results).hasSize(cases.size());
    }

    private Callable<EvalObservation> observe(ReturnReasonClassifier classifier, EvalCase c) {
        return () -> classifier.classify(c.reason())
                .map(r -> new EvalObservation(c.id(), r.category(), r.confidence(),
                        r.suggestedDisposition(), r.inputTokens(), r.outputTokens(), r.model()))
                .orElseGet(() -> EvalObservation.failed(c.id()));
    }
}
