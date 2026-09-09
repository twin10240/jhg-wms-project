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

    /**
     * 배분은 중립이 아니라 가설을 겨냥한 값이다. 그래서 숫자가 바뀌면 왜 바뀌었는지가 같이 남아야 한다.
     * 30 → 38: 3회차(프롬프트 수정 후) OTHER 8/8이 과적합인지 가리려고 OTHER에 5건,
     * 좁힌 CHANGED_MIND가 과교정인지 보려고 그쪽에 3건을 더했다.
     */
    @Test
    void 서른여덟_건이_설계대로_배분돼_있다() {
        Map<ReturnCategory, Long> 배분 = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::expectedCategory, Collectors.counting()));

        assertThat(cases).hasSize(38);
        assertThat(배분).containsExactlyInAnyOrderEntriesOf(Map.of(
                ReturnCategory.DAMAGED, 7L,
                ReturnCategory.WRONG_ITEM, 7L,
                ReturnCategory.CHANGED_MIND, 11L,
                ReturnCategory.OTHER, 13L));
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
