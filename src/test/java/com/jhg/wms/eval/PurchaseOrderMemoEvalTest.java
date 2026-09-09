package com.jhg.wms.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudePurchaseOrderMemoClassifier;
import com.jhg.wms.domain.PurchaseOrderMemoCategory;
import com.jhg.wms.service.PurchaseOrderMemoClassifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발주 메모 분류의 품질 평가. 구조는 {@link ClassificationEvalTest}와 같고, 다른 것은 셋뿐이다 —
 * 평가셋 파일, 분류기, 범주 목록. 하네스(EvalCase·EvalAggregator·EvalReportWriter)는 그대로 쓴다.
 *
 * <p>처분이 없다. 발주 메모 분류는 category·confidence·evidence만 내므로 관측의 disposition은
 * 항상 null이고, 리포트도 처분 매핑 절을 내지 않는다.
 *
 * <p>점수로 실패하지 않는 것도 같다. 실패 조건은 오직 "러너가 못 돌았다"이다.
 */
@Tag("eval")
class PurchaseOrderMemoEvalTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final int REPEATS = 3;
    private static final int PARALLELISM = 5;
    private static final Path REPORT = Path.of("build/reports/memo-classification-eval.md");
    private static final String CASES = "eval/purchase-order-memos.json";
    private static final List<String> CATEGORIES =
            Arrays.stream(PurchaseOrderMemoCategory.values()).map(Enum::name).toList();

    @Test
    void 메모_분류_품질을_재고_리포트를_남긴다() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY 미설정 — 평가를 건너뜁니다.");

        PurchaseOrderMemoClassifier classifier = new ClaudePurchaseOrderMemoClassifier(
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .timeout(Duration.ofSeconds(20))
                        .maxRetries(1)
                        .build(),
                new ObjectMapper(), MODEL, 1024L);

        List<EvalCase> cases = EvalCase.loadAll(CASES);
        List<EvalAggregator.CaseResult> results = new ArrayList<>();
        String 실제모델 = MODEL;

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

        for (EvalAggregator.CaseResult r : results)
            for (EvalObservation o : r.observations())
                if (o.succeeded() && o.model() != null) { 실제모델 = o.model(); break; }

        var summary = EvalAggregator.summarize(results, CATEGORIES);
        String report = EvalReportWriter.render(실제모델, REPEATS, results, summary, CATEGORIES);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        assertThat(results).hasSize(cases.size());
    }

    private Callable<EvalObservation> observe(PurchaseOrderMemoClassifier classifier, EvalCase c) {
        // disposition은 null이다 — 이 분류기는 처분을 내지 않는다.
        return () -> classifier.classify(c.reason())
                .map(r -> new EvalObservation(c.id(), r.category().name(), r.confidence(),
                        null, r.inputTokens(), r.outputTokens(), r.model()))
                .orElseGet(() -> EvalObservation.failed(c.id()));
    }
}
