package com.jhg.wms.eval;

import com.jhg.wms.domain.PurchaseOrderMemoCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 발주 메모 평가셋의 형식 검증. 유료 실행 전에 공짜로 잡는다 — {@link EvalCaseLoadTest}와 같은 목적. */
class MemoEvalCaseLoadTest {

    private final List<EvalCase> cases = EvalCase.loadAll("eval/purchase-order-memos.json");

    /**
     * 배분은 중립이 아니라 가설을 겨냥한 값이다. OTHER가 7건으로 가장 많은 이유 —
     * 잔여 범주라 가장 헷갈리고, 반품 평가에서도 틀린 건이 전부 OTHER에서 나왔다.
     */
    @Test
    void 서른_건이_설계대로_배분돼_있다() {
        Map<String, Long> 배분 = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::expectedCategory, Collectors.counting()));

        assertThat(cases).hasSize(30);
        assertThat(배분).containsExactlyInAnyOrderEntriesOf(Map.of(
                PurchaseOrderMemoCategory.URGENT_STOCKOUT.name(), 5L,
                PurchaseOrderMemoCategory.ROUTINE.name(), 4L,
                PurchaseOrderMemoCategory.DEMAND_EVENT.name(), 5L,
                PurchaseOrderMemoCategory.SUPPLIER.name(), 5L,
                PurchaseOrderMemoCategory.QUALITY.name(), 4L,
                PurchaseOrderMemoCategory.OTHER.name(), 7L));
    }

    // 범주가 String이라 오타가 컴파일에서 안 걸린다. 그 자리를 여기가 대신 지킨다.
    @Test
    void 모든_라벨이_PurchaseOrderMemoCategory의_값이다() {
        assertThat(cases).allSatisfy(c ->
                assertThatCode(() -> PurchaseOrderMemoCategory.valueOf(c.expectedCategory()))
                        .as("expectedCategory: %s", c.id()).doesNotThrowAnyException());
    }

    @Test
    void id가_겹치지_않는다() {
        assertThat(cases.stream().map(EvalCase::id).distinct()).hasSize(cases.size());
    }

    @Test
    void 모든_케이스에_사유와_근거가_있다() {
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.reason()).as("reason: %s", c.id()).isNotBlank();
            assertThat(c.note()).as("note: %s", c.id()).isNotBlank();
        });
    }
}
