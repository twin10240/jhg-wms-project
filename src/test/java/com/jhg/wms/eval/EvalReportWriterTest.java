package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportWriterTest {

    private EvalAggregator.CaseResult 결과(String id, ReturnCategory expected, ReturnCategory got) {
        var c = new EvalCase(id, "사유 " + id, expected, "테스트용");
        return EvalAggregator.toCaseResult(c, List.of(
                new EvalObservation(id, got, Confidence.HIGH, RmaDisposition.DISPOSED, 1000, 40,
                        "claude-haiku-4-5-20251001")));
    }

    // 모델 스냅샷이 없는 점수는 나중에 해석되지 않는다. 리포트에 반드시 있어야 한다.
    @Test
    void 리포트에_모델_스냅샷과_정확도가_들어간다() {
        var results = List.of(결과("a", ReturnCategory.DAMAGED, ReturnCategory.DAMAGED),
                              결과("b", ReturnCategory.OTHER, ReturnCategory.CHANGED_MIND));
        String report = EvalReportWriter.render(
                "claude-haiku-4-5-20251001", 3, results, EvalAggregator.summarize(results));

        assertThat(report)
                .contains("claude-haiku-4-5-20251001")
                .contains("1/2")
                .contains("처분 매핑")
                .contains("누적 토큰");
    }

    // 틀린 케이스는 id와 note까지 나와야 한다 — 왜 틀렸는지 보려면 그 케이스를 찾아가야 한다.
    @Test
    void 틀린_케이스를_id와_함께_나열한다() {
        var results = List.of(결과("b", ReturnCategory.OTHER, ReturnCategory.CHANGED_MIND));
        String report = EvalReportWriter.render(
                "claude-haiku-4-5-20251001", 3, results, EvalAggregator.summarize(results));

        assertThat(report).contains("b").contains("OTHER").contains("CHANGED_MIND");
    }

    /**
     * 1회차(2026-09-01)가 여기서 막혔다. 리포트가 케이스 단위 요약만 내서
     * (1) 같은 케이스 안에서 처분이 갈린 것을 산술로만 추론했고
     * (2) 틀린 케이스가 어떤 신뢰도를 받았는지 확인하지 못했다.
     * 관측 하나하나를 표로 내야 다음 회차에서 같은 자리에 다시 막히지 않는다.
     */
    @Test
    void 케이스별로_관측_하나하나의_범주_처분_신뢰도를_낸다() {
        var c = new EvalCase("x", "사유 x", ReturnCategory.DAMAGED, "테스트용");
        var results = List.of(EvalAggregator.toCaseResult(c, List.of(
                new EvalObservation("x", ReturnCategory.DAMAGED, Confidence.HIGH,
                        RmaDisposition.DISPOSED, 100, 10, "claude-haiku-4-5-20251001"),
                new EvalObservation("x", ReturnCategory.DAMAGED, Confidence.LOW,
                        RmaDisposition.RESTOCKED, 100, 10, "claude-haiku-4-5-20251001"),
                EvalObservation.failed("x"))));

        String report = EvalReportWriter.render(
                "claude-haiku-4-5-20251001", 3, results, EvalAggregator.summarize(results));

        assertThat(report).contains("케이스별 관측");
        String 절 = report.substring(report.indexOf("케이스별 관측"));
        assertThat(절)
                .contains("DISPOSED").contains("RESTOCKED")   // 같은 범주 안에서 처분이 갈린 것
                .contains("HIGH").contains("LOW")             // 관측별 신뢰도
                .contains("실패");                             // 분류 실패도 자리를 비우지 않고 적는다
    }
}
