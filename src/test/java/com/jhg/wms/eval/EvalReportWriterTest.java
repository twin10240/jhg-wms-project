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
}
