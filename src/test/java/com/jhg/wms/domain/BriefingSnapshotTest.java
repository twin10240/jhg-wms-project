package com.jhg.wms.domain;

import com.jhg.wms.service.PurchaseOrderAdviceService.LastOrder;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingSnapshotTest {

    private ProductAdvice advice(long id, String name, Double daysToStockout) {
        return new ProductAdvice(id, name, 60, 30L, 2.0, 20, 5, 15, daysToStockout,
                new LastOrder(900L, PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 9, 1), 50,
                        LocalDate.of(2026, 9, 3), 2L));
    }

    @Test
    void 상위_N개만_담는다() {
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10),
                List.of(advice(1, "A", 1.0), advice(2, "B", 2.0), advice(3, "C", 3.0)), 2);

        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows()).extracting(BriefingSnapshot.Row::productId).containsExactly(1L, 2L);
    }

    // 근거 패널은 이미 소진 임박 순으로 정렬해서 준다. 여기서 다시 정렬하지 않는다 —
    // 정렬 규칙이 두 군데 있으면 갈라진다.
    @Test
    void 받은_순서를_그대로_쓴다() {
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10),
                List.of(advice(3, "C", 3.0), advice(1, "A", 1.0)), 5);

        assertThat(snapshot.rows()).extracting(BriefingSnapshot.Row::productId).containsExactly(3L, 1L);
    }

    // 직전 발주가 없는 상품이 있다. null을 그대로 담아야 프롬프트가 "없음"으로 렌더한다.
    @Test
    void 직전_발주가_없으면_null이다() {
        var noLast = new ProductAdvice(7L, "G", 0, 5L, 0.0, 3, 0, 3, null, null);

        var row = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(noLast), 5).rows().get(0);

        assertThat(row.lastOrderId()).isNull();
        assertThat(row.lastOrderedOn()).isNull();
        assertThat(row.lastOrderQty()).isNull();
        assertThat(row.daysToStockout()).isNull();
        assertThat(row.lastOrderStatus()).isNull();
        assertThat(row.lastOrderReceivedOn()).isNull();
    }

    // 측정 3회차 사고: 모델이 "직전 발주가 100개를 받았으나"라며 입고를 단정했다. 표에
    // 입고 여부가 없어서 벌어진 일이라 상태·입고일이 스냅샷까지 제대로 실려야 한다.
    @Test
    void 직전_발주_상태와_입고일을_담는다() {
        var advice = new ProductAdvice(7L, "G", 60, 30L, 2.0, 20, 5, 15, 1.0,
                new LastOrder(900L, PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 9, 1), 50,
                        LocalDate.of(2026, 9, 3), 2L));

        var row = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(advice), 5).rows().get(0);

        assertThat(row.lastOrderStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(row.lastOrderReceivedOn()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    // 아직 입고되지 않은 발주는 상태만 있고 입고일은 null이다 — 이게 바로 이 변경의 요점이다:
    // 발주 수량이 있다고 해서 입고를 지어내면 안 된다.
    @Test
    void 미입고_발주는_입고일이_null이다() {
        var advice = new ProductAdvice(7L, "G", 60, 30L, 2.0, 20, 5, 15, 1.0,
                new LastOrder(900L, PurchaseOrderStatus.ORDERED, LocalDate.of(2026, 9, 1), 50,
                        null, null));

        var row = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(advice), 5).rows().get(0);

        assertThat(row.lastOrderStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(row.lastOrderReceivedOn()).isNull();
    }

    // 두 차례 평가에서 모델이 매번 지어낸 값이 이 경과일이었다 — 표에 없어서 계산했다.
    // 이제 표에 값을 주므로 직접 계산해서 맞는지 검증한다.
    @Test
    void 직전_발주_이후_경과일을_계산한다() {
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(advice(1, "A", 1.0)), 1);

        assertThat(snapshot.daysSinceLastOrder(snapshot.rows().get(0))).isEqualTo(9L);
    }

    @Test
    void 직전_발주가_없으면_경과일도_null이다() {
        var noLast = new ProductAdvice(7L, "G", 0, 5L, 0.0, 3, 0, 3, null, null);
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(noLast), 1);

        assertThat(snapshot.daysSinceLastOrder(snapshot.rows().get(0))).isNull();
    }

    @Test
    void 당일_발주면_0일이다() {
        var row = new BriefingSnapshot.Row(1L, "A", 60, 30L, 2.0, 15, 1.0,
                900L, LocalDate.of(2026, 9, 10), 50,
                PurchaseOrderStatus.ORDERED, null);
        var snapshot = new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(row));

        assertThat(snapshot.daysSinceLastOrder(row)).isEqualTo(0L);
    }
}
