package com.jhg.wms.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudePurchaseOrderBriefingGenerator;
import com.jhg.wms.service.PurchaseOrderBriefingGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브리핑 생성의 품질 평가. 구조는 {@link ClassificationEvalTest}와 같고 다른 것은 채점이다 —
 * 정답 라벨이 없으므로 기계 채점(숫자 대조)과 judge를 나눠 쓴다.
 *
 * <p>점수로 실패하지 않는 것은 같다. 실패 조건은 오직 "러너가 못 돌았다"이다.
 */
@Tag("eval")
class BriefingEvalTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final int JUDGE_REPEATS = 3;
    private static final Path REPORT = Path.of("build/reports/briefing-eval.md");

    @Test
    void 브리핑_품질을_재고_리포트를_남긴다() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY 미설정 — 평가를 건너뜁니다.");

        var client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey).timeout(Duration.ofSeconds(60)).maxRetries(1).build();
        var objectMapper = new ObjectMapper();
        PurchaseOrderBriefingGenerator generator =
                new ClaudePurchaseOrderBriefingGenerator(client, MODEL, 2048L);
        BriefingJudge judge = new BriefingJudge(client, objectMapper, MODEL, 1024L);

        List<BriefingEvalCase> cases = BriefingEvalCase.loadAll("eval/briefing-cases.json");
        List<BriefingReportWriter.CaseScore> scores = new ArrayList<>();
        String 실제모델 = MODEL;

        for (BriefingEvalCase c : cases) {
            // 네거티브는 생성하지 않는다. 본문이 이미 있다.
            String body = c.isNegative() ? c.body()
                    : generator.generate(c.snapshot()).map(PurchaseOrderBriefingGenerator.Briefing::body)
                            .orElse(null);
            if (body == null) {
                scores.add(new BriefingReportWriter.CaseScore(c.id(), c.isNegative(), 0, 0,
                        Map.of(), List.of(), "생성 실패"));
                continue;
            }

            var grounding = GroundingScorer.score(body, c.snapshot());

            // GROUNDING 네거티브는 judge에게 보내지 않는다 — 기계가 잡아야 하고 호출이 필요 없다.
            Map<String, String> majority = new LinkedHashMap<>();
            List<String> unstable = new ArrayList<>();
            if (!"GROUNDING".equals(c.failingItem())) {
                List<Map<String, Boolean>> verdicts = new ArrayList<>();
                for (int i = 0; i < JUDGE_REPEATS; i++) {
                    Optional<BriefingJudge.Verdict> v = judge.judge(body, c.snapshot());
                    if (v.isPresent()) {
                        verdicts.add(v.get().items());
                        실제모델 = v.get().model();
                    }
                }
                List<String> items = c.isNegative() ? List.of(c.failingItem()) : BriefingJudge.ITEMS;
                for (String item : items) {
                    // 항목 하나 = 값이 YES/NO 둘뿐인 분류. 기존 집계를 그대로 쓴다.
                    var source = new EvalCase(c.id() + ":" + item, "", "YES", c.note());
                    var observations = verdicts.stream()
                            .map(v -> new EvalObservation(source.id(),
                                    Boolean.TRUE.equals(v.get(item)) ? "YES" : "NO",
                                    null, null, 0, 0, MODEL))
                            .toList();
                    var result = EvalAggregator.toCaseResult(source, observations);
                    majority.put(item, result.majority());
                    if (result.unstable()) unstable.add(item);
                }
            } else {
                majority.put("GROUNDING", grounding.clean() ? "YES" : "NO");
            }

            scores.add(new BriefingReportWriter.CaseScore(c.id(), c.isNegative(),
                    grounding.total(), grounding.ungrounded().size(), majority, unstable,
                    c.note() == null ? "" : c.note()));
        }

        String report = BriefingReportWriter.render(실제모델, JUDGE_REPEATS, scores);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        assertThat(scores).hasSize(cases.size());
    }
}
