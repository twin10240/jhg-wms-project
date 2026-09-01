package com.jhg.wms.eval;

import com.jhg.wms.domain.ReturnCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가셋 자체를 검증한다. 유료 실행을 돌린 뒤에야 "id가 겹쳤다"를 아는 일이 없도록,
 * 형식 문제는 공짜로 먼저 잡는다.
 */
class EvalCaseLoadTest {

    private final List<EvalCase> cases = EvalCase.loadAll();

    @Test
    void 서른_건이_설계대로_배분돼_있다() {
        Map<ReturnCategory, Long> 배분 = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::expectedCategory, Collectors.counting()));

        assertThat(cases).hasSize(30);
        assertThat(배분).containsExactlyInAnyOrderEntriesOf(Map.of(
                ReturnCategory.DAMAGED, 7L,
                ReturnCategory.WRONG_ITEM, 7L,
                ReturnCategory.CHANGED_MIND, 8L,
                ReturnCategory.OTHER, 8L));
    }

    @Test
    void id가_겹치지_않는다() {
        assertThat(cases.stream().map(EvalCase::id).distinct()).hasSize(cases.size());
    }

    // note 없는 케이스는 나중에 해석이 불가능해진다. 라벨만 남는 것을 막는다.
    @Test
    void 모든_케이스에_사유와_근거가_있다() {
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.reason()).as("reason: %s", c.id()).isNotBlank();
            assertThat(c.note()).as("note: %s", c.id()).isNotBlank();
        });
    }
}
