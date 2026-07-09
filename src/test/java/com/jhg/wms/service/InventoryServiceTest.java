package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest
class InventoryServiceTest {

    @Autowired InventoryRepository repo;
    @Autowired ReservationRepository reservationRepo;
    InventoryService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        service = new InventoryService(repo, reservationRepo, notifier);
    }

    private void seed(long pid, int qty) {
        repo.save(Inventory.create(pid, qty));
    }

    @Test
    void reserveAll_전상품_가용하면_예약하고_true() {
        seed(1L, 10); seed(2L, 5);
        boolean result = service.reserveAll(99L, Map.of(1L, 3, 2L, 4));
        assertThat(result).isTrue();
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(3);
        assertThat(repo.findByProductIdIn(List.of(2L)).get(0).getReservedQty()).isEqualTo(4);
    }

    @Test
    void reserveAll_하나라도_부족하면_아무것도_예약않고_false() {
        seed(1L, 10); seed(2L, 2);
        boolean result = service.reserveAll(99L, Map.of(1L, 3, 2L, 5));
        assertThat(result).isFalse();
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(0);
        assertThat(repo.findByProductIdIn(List.of(2L)).get(0).getReservedQty()).isEqualTo(0);
    }

    @Test
    void shipAll_예약후_출고하면_보유와_예약이_줄어든다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(4);
        assertThat(after.getReservedQty()).isEqualTo(0);
    }

    @Test
    void releaseAll_예약후_해제하면_예약분이_복구된다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.releaseAll(99L, Map.of(1L, 6));
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getReservedQty()).isEqualTo(0);
        assertThat(after.getOnHandQty()).isEqualTo(10);
    }

    // ── adjust / rows ──────────────────────────────────────────

    @Test
    void adjust_재고를_증가시킨다() {
        seed(1L, 10);
        int result = service.adjust(1L, 5);
        assertThat(result).isEqualTo(15);
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()).isEqualTo(15);
    }

    @Test
    void adjust_재고가_음수가_되면_예외를_던진다() {
        seed(1L, 5);
        assertThatThrownBy(() -> service.adjust(1L, -10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()).isEqualTo(5);
    }

    @Test
    void findAllRows_전체_재고행을_productId_오름차순으로_반환한다() {
        seed(2L, 20); seed(1L, 10);
        var rows = service.findAllRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).productId()).isEqualTo(1L);
        assertThat(rows.get(1).productId()).isEqualTo(2L);
    }

    @Test
    void findAllRows_예약수량과_가용수량을_포함한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 3));
        var rows = service.findAllRows();
        assertThat(rows.get(0).onHandQty()).isEqualTo(10);
        assertThat(rows.get(0).reservedQty()).isEqualTo(3);
        assertThat(rows.get(0).availableQty()).isEqualTo(7);
    }

    @Test
    void adjust_증가면_커밋_후_OMS_통지를_예약한다() {
        seed(1L, 10);
        service.adjust(1L, 5);
        verify(notifier).notifyAfterCommit(1L);
    }

    @Test
    void adjust_감소면_OMS_통지를_예약하지_않는다() {
        seed(1L, 10);
        service.adjust(1L, -3);
        verify(notifier, never()).notifyAfterCommit(any());
    }

    // ── 멱등성 ──────────────────────────────────────────────────

    @Test
    void reserveAll_같은_orderId_재호출은_이중예약_없이_true() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        boolean second = service.reserveAll(99L, Map.of(1L, 6));
        assertThat(second).isTrue();
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(6);
    }

    @Test
    void shipAll_이미_출고됐으면_노옵() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6)); // 두 번째: no-op, 예외 없음
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(4); // 한 번만 차감됨
    }

    @Test
    void releaseAll_예약없으면_노옵() {
        seed(1L, 10);
        service.releaseAll(99L, Map.of(1L, 6)); // 예약 없이 해제 → no-op, 예외 없음
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(0);
    }

    @Test
    void shipAll_해제된_예약이면_예외를_던지고_재고는_불변이다() {
        // 취소 release가 처리됐는데 응답만 타임아웃난 반쪽 상태에서 출고가 들어온 시나리오 —
        // 가드 없으면 reservedQty가 음수로 내려가 가용수량이 부풀려진다(침묵 오염).
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.releaseAll(99L, Map.of(1L, 6));

        assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, 6)))
                .isInstanceOf(IllegalStateException.class);

        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(10);
        assertThat(after.getReservedQty()).isEqualTo(0);
    }

    @Test
    void releaseAll_출고된_예약이면_예외를_던지고_재고는_불변이다() {
        // 출고가 처리됐는데 응답만 타임아웃난 반쪽 상태에서 취소가 들어온 시나리오 —
        // 가드 없으면 reservedQty가 음수로 내려가 가용수량이 부풀려진다(shipAll 가드의 대칭).
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));

        assertThatThrownBy(() -> service.releaseAll(99L, Map.of(1L, 6)))
                .isInstanceOf(IllegalStateException.class);

        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(4);
        assertThat(after.getReservedQty()).isEqualTo(0);
    }
}
