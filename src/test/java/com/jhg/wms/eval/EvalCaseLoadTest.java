package com.jhg.wms.eval;

import com.jhg.wms.domain.ReturnCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 평가셋 자체를 검증한다. 유료 실행을 돌린 뒤에야 "id가 겹쳤다"를 아는 일이 없도록,
 * 형식 문제는 공짜로 먼저 잡는다.
 */
class EvalCaseLoadTest {

    private final List<EvalCase> cases = EvalCase.loadAll("eval/return-reasons.json");

    /**
     * 배분은 중립이 아니라 가설을 겨냥한 값이다. 그래서 숫자가 바뀌면 왜 바뀌었는지가 같이 남아야 한다.
     * 30 → 38: 3회차(프롬프트 수정 후) OTHER 8/8이 과적합인지 가리려고 OTHER에 5건,
     * 좁힌 CHANGED_MIND가 과교정인지 보려고 그쪽에 3건을 더했다.
     * 38 → 41: 프롬프트에 "주문 착오" 줄을 넣으면서, 그것이 other-13 한 건을 외운 것인지
     * 규칙으로 도는지 가리려고 다른 모양의 착오 3건(수량·선택·배송지)을 더했다.
     */
    @Test
    void 마흔한_건이_설계대로_배분돼_있다() {
        Map<String, Long> 배분 = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::expectedCategory, Collectors.counting()));

        assertThat(cases).hasSize(41);
        assertThat(배분).containsExactlyInAnyOrderEntriesOf(Map.of(
                ReturnCategory.DAMAGED.name(), 7L,
                ReturnCategory.WRONG_ITEM.name(), 7L,
                ReturnCategory.CHANGED_MIND.name(), 11L,
                ReturnCategory.OTHER.name(), 16L));
    }

    // 범주가 String이라 오타가 컴파일에서 안 걸린다. 그 자리를 여기가 대신 지킨다.
    @Test
    void 모든_라벨이_ReturnCategory의_값이다() {
        assertThat(cases).allSatisfy(c ->
                assertThatCode(() -> ReturnCategory.valueOf(c.expectedCategory()))
                        .as("expectedCategory: %s", c.id()).doesNotThrowAnyException());
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
