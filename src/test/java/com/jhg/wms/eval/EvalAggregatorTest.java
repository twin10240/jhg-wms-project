package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 집계를 API 없이 검증한다. 집계가 틀리면 유료 실행 결과를 통째로 잘못 읽게 되므로,
 * 이 부분은 공짜로 확인해야 한다.
 */
class EvalAggregatorTest {

    private EvalCase 케이스(String id, ReturnCategory expected) {
        return new EvalCase(id, "사유 " + id, expected, "테스트용");
    }

    private EvalObservation 관측(String id, ReturnCategory c, Confidence conf, RmaDisposition d) {
        return new EvalObservation(id, c, conf, d, 1000, 40, "claude-haiku-4-5-20251001");
    }

    @Test
    void 세_번_같은_답이면_안정이고_그_답이_다수결이다() {
        var result = EvalAggregator.toCaseResult(
                케이스("a", ReturnCategory.DAMAGED),
                List.of(관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        assertThat(result.majority()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(result.unstable()).isFalse();
    }

    @Test
    void 답이_갈리면_흔들림으로_표시하고_최빈값을_다수결로_삼는다() {
        var result = EvalAggregator.toCaseResult(
                케이스("b", ReturnCategory.OTHER),
                List.of(관측("b", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("b", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("b", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED)));

        assertThat(result.majority()).isEqualTo(ReturnCategory.OTHER);
        assertThat(result.unstable()).isTrue();
    }

    // 세 답이 모두 다르면 최빈값이 없다. 이때는 다수결을 null로 두어
    // "판단 불가"를 오답과 구분한다 — 오답으로 세면 정확도가 실제보다 나빠 보인다.
    @Test
    void 세_답이_모두_다르면_다수결이_없다() {
        var result = EvalAggregator.toCaseResult(
                케이스("c", ReturnCategory.OTHER),
                List.of(관측("c", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("c", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("c", ReturnCategory.DAMAGED, Confidence.LOW, RmaDisposition.DISPOSED)));

        assertThat(result.majority()).isNull();
        assertThat(result.unstable()).isTrue();
    }

    @Test
    void 실패한_관측은_다수결에서_빠지고_따로_센다() {
        var result = EvalAggregator.toCaseResult(
                케이스("d", ReturnCategory.DAMAGED),
                List.of(관측("d", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        EvalObservation.failed("d"),
                        관측("d", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(result));

        assertThat(result.majority()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(result.unstable()).isFalse();
        assertThat(summary.failedObservations()).isEqualTo(1);
    }

    @Test
    void 요약이_정확도와_범주별_성적을_낸다() {
        var 맞음 = EvalAggregator.toCaseResult(
                케이스("e", ReturnCategory.DAMAGED),
                List.of(관측("e", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));
        var 틀림 = EvalAggregator.toCaseResult(
                케이스("f", ReturnCategory.OTHER),
                List.of(관측("f", ReturnCategory.CHANGED_MIND, Confidence.MEDIUM, RmaDisposition.RESTOCKED)));

        var summary = EvalAggregator.summarize(List.of(맞음, 틀림));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.correct()).isEqualTo(1);
        assertThat(summary.perCategory().get(ReturnCategory.DAMAGED)).containsExactly(1, 1);
        assertThat(summary.perCategory().get(ReturnCategory.OTHER)).containsExactly(0, 1);
    }

    @Test
    void 처분_매핑을_범주별로_모은다() {
        var a = EvalAggregator.toCaseResult(
                케이스("g", ReturnCategory.CHANGED_MIND),
                List.of(관측("g", ReturnCategory.CHANGED_MIND, Confidence.HIGH, RmaDisposition.RESTOCKED),
                        관측("g", ReturnCategory.CHANGED_MIND, Confidence.HIGH, RmaDisposition.REJECTED)));

        var summary = EvalAggregator.summarize(List.of(a));

        assertThat(summary.dispositionByCategory().get(ReturnCategory.CHANGED_MIND))
                .containsEntry(RmaDisposition.RESTOCKED, 1)
                .containsEntry(RmaDisposition.REJECTED, 1);
    }

    // confidence가 제 역할을 하는지 보려면 "흔들린 케이스에서 낮게 주는가"를 봐야 한다.
    @Test
    void 흔들린_케이스의_신뢰도를_따로_모은다() {
        var 흔들림 = EvalAggregator.toCaseResult(
                케이스("h", ReturnCategory.OTHER),
                List.of(관측("h", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("h", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("h", ReturnCategory.OTHER, Confidence.MEDIUM, RmaDisposition.RESTOCKED)));
        var 안정 = EvalAggregator.toCaseResult(
                케이스("i", ReturnCategory.DAMAGED),
                List.of(관측("i", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(흔들림, 안정));

        assertThat(summary.confidenceOfUnstable())
                .containsEntry(Confidence.LOW, 2)
                .containsEntry(Confidence.MEDIUM, 1)
                .doesNotContainKey(Confidence.HIGH);
        assertThat(summary.confidenceDistribution()).containsEntry(Confidence.HIGH, 1);
    }

    @Test
    void 토큰을_합산한다() {
        var a = EvalAggregator.toCaseResult(
                케이스("j", ReturnCategory.DAMAGED),
                List.of(관측("j", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("j", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(a));

        assertThat(summary.inputTokens()).isEqualTo(2000);
        assertThat(summary.outputTokens()).isEqualTo(80);
    }
}
