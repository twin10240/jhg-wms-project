package com.jhg.wms.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가셋의 형식 오류를 컴파일이 못 잡으므로 여기서 잡는다.
 * {@code EvalCaseLoadTest}·{@code MemoEvalCaseLoadTest}와 같은 이유다.
 */
class BriefingEvalCaseLoadTest {

    private final List<BriefingEvalCase> cases = BriefingEvalCase.loadAll("eval/briefing-cases.json");

    @Test
    void 정상_여덟_건과_네거티브_네_건이다() {
        assertThat(cases.stream().filter(c -> !c.isNegative())).hasSize(8);
        assertThat(cases.stream().filter(BriefingEvalCase::isNegative)).hasSize(4);
    }

    @Test
    void id가_겹치지_않는다() {
        assertThat(cases).extracting(BriefingEvalCase::id).doesNotHaveDuplicates();
    }

    // failingItem이 오타면 그 네거티브는 영원히 통과한다 — 채점할 항목을 못 찾으니까.
    @Test
    void 네거티브의_failingItem은_judge_항목이거나_GROUNDING이다() {
        assertThat(cases.stream().filter(BriefingEvalCase::isNegative))
                .allSatisfy(c -> assertThat(c.failingItem())
                        .isIn(java.util.stream.Stream.concat(
                                BriefingJudge.ITEMS.stream(), java.util.stream.Stream.of("GROUNDING"))
                                .toList()));
    }

    @Test
    void 정상_케이스는_본문이_없고_네거티브는_있다() {
        assertThat(cases).allSatisfy(c -> {
            if (c.isNegative()) assertThat(c.body()).isNotBlank();
            else assertThat(c.body()).isNull();
        });
    }

    // 같은 모양을 여덟 번 재면 표본이 하나다. 최소한 소진 임박 상품 수는 갈려 있어야 한다.
    @Test
    void 정상_케이스의_재고_모양이_서로_다르다() {
        var urgentCounts = cases.stream().filter(c -> !c.isNegative())
                .map(c -> c.snapshot().rows().stream()
                        .filter(r -> r.daysToStockout() != null && r.daysToStockout() < 3).count())
                .distinct().toList();

        assertThat(urgentCounts).hasSizeGreaterThan(2);
    }
}
