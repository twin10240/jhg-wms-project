package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.repository.InventoryTransactionRepository;
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
    @Autowired InventoryTransactionRepository adjustmentRepo;
    InventoryService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        service = new InventoryService(repo, reservationRepo, adjustmentRepo, notifier);
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

    // ── 쓰기 입구 검증(음수/0 수량) ────────────────────────────

    @Test
    void reserveAll_음수_수량은_거부하고_예약을_변경하지_않는다() {
        seed(1L, 10);
        assertThatThrownBy(() -> service.reserveAll(99L, Map.of(1L, -5)))
                .isInstanceOf(IllegalArgumentException.class);
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getReservedQty()).isEqualTo(0); // 음수 예약이 reservedQty를 깎아 가용을 부풀리면 안 됨
    }

    @Test
    void reserveAll_0_수량은_거부한다() {
        seed(1L, 10);
        assertThatThrownBy(() -> service.reserveAll(99L, Map.of(1L, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shipAll_음수_수량은_거부한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, -3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releaseAll_음수_수량은_거부한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        assertThatThrownBy(() -> service.releaseAll(99L, Map.of(1L, -3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── adjust / rows ──────────────────────────────────────────

    @Test
    void adjust_재고를_증가시킨다() {
        seed(1L, 10);
        int result = service.adjust(1L, 5, "정기실사");
        assertThat(result).isEqualTo(15);
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()).isEqualTo(15);
    }

    @Test
    void adjust_재고가_음수가_되면_예외를_던진다() {
        seed(1L, 5);
        assertThatThrownBy(() -> service.adjust(1L, -10, "정기실사"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()).isEqualTo(5);
    }

    @Test
    void adjust_수동조정은_내역을_before_after_사유와_함께_남긴다() {
        seed(1L, 10);
        service.adjust(1L, 5, "정기실사");
        var log = adjustmentRepo.findAllByOrderByIdDesc();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getProductId()).isEqualTo(1L);
        assertThat(log.get(0).getDelta()).isEqualTo(5);
        assertThat(log.get(0).getBeforeQty()).isEqualTo(10);
        assertThat(log.get(0).getAfterQty()).isEqualTo(15);
        assertThat(log.get(0).getReason()).isEqualTo("정기실사");
        assertThat(log.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void adjust_수동조정하면_ADJUST_트랜잭션이_남는다() {
        seed(1L, 10);
        service.adjust(1L, -3, "파손");
        var txns = adjustmentRepo.findAllByOrderByIdDesc();
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).getType()).isEqualTo(com.jhg.wms.domain.InventoryTransactionType.ADJUST);
        assertThat(txns.get(0).getDelta()).isEqualTo(-3);
        assertThat(txns.get(0).getBeforeQty()).isEqualTo(10);
        assertThat(txns.get(0).getAfterQty()).isEqualTo(7);
        assertThat(txns.get(0).getReason()).isEqualTo("파손");
        assertThat(txns.get(0).getReference()).isNull();
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
        service.adjust(1L, 5, "정기실사");
        verify(notifier).notifyAfterCommit(1L);
    }

    @Test
    void adjust_감소면_OMS_통지를_예약하지_않는다() {
        seed(1L, 10);
        service.adjust(1L, -3, "정기실사");
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

    @Test
    void findAllReservations_ID_역순으로_반환한다() {
        reservationRepo.save(Reservation.reserve(1L, Map.of(1L, 1)));
        reservationRepo.save(Reservation.reserve(2L, Map.of(1L, 1)));
        var list = service.findAllReservations();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getOrderId()).isEqualTo(2L);
    }

    // ── T1: 동시 예약 경합(가용분 부족 시 check-then-act 방어) ────────────

    @Test
    void reserveAll_가용분이_부족하면_두번째_예약은_실패한다() {
        // 시나리오: 재고 5개, 같은 상품에서 두 orderId가 각각 3개 예약 시도
        // ① orderId=1, qty=3 → 성공 (available=5 >= 3), reserved=3
        // ② orderId=2, qty=3 → 실패 (available=2 < 3), reserved 변경 없음 (= 3)
        // check-then-act 방어: 첫 번째만 성공해야 함 (가용분 경합의 계약)
        seed(1L, 5);

        // 첫 번째 예약: 성공
        boolean first = service.reserveAll(1L, Map.of(1L, 3));
        assertThat(first).isTrue();
        Inventory after1st = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after1st.getReservedQty()).isEqualTo(3);
        assertThat(after1st.getAvailableQty()).isEqualTo(2); // 5 - 3 = 2

        // 두 번째 예약: 실패 (가용 2 < 요청 3)
        boolean second = service.reserveAll(2L, Map.of(1L, 3));
        assertThat(second).isFalse();
        Inventory after2nd = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after2nd.getReservedQty()).isEqualTo(3); // 변경 없음
        assertThat(after2nd.getAvailableQty()).isEqualTo(2); // 변경 없음
    }

    @Test
    void reserveAll_경합시_부분예약_없이_전체_롤백한다() {
        // 시나리오: 다중 상품 예약에서 하나만 부족 → 전부 실패(원자성)
        // ① productId=1: qty=3 (available=5 충분)
        // ② productId=2: qty=4 (available=2 부족)
        // → 둘 다 예약 안 함
        seed(1L, 5); seed(2L, 2);

        boolean result = service.reserveAll(99L, Map.of(1L, 3, 2L, 4));
        assertThat(result).isFalse();

        Inventory inv1 = repo.findByProductIdIn(List.of(1L)).get(0);
        Inventory inv2 = repo.findByProductIdIn(List.of(2L)).get(0);
        assertThat(inv1.getReservedQty()).isEqualTo(0); // 부분 예약 없음
        assertThat(inv2.getReservedQty()).isEqualTo(0); // 부분 예약 없음
    }

    // ── P0-2: 예약 원장(SSOT) 기반 ship/release ────────────────────

    @Test
    void reserveAll_예약수량을_원장에_저장한다() {
        seed(1L, 10); seed(2L, 5);
        service.reserveAll(99L, Map.of(1L, 3, 2L, 4));
        var reservation = reservationRepo.findByOrderId(99L).orElseThrow();
        assertThat(reservation.getQtyByProductId())
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3, 2L, 4));
    }

    @Test
    void shipAll_호출자가_잘못된_수량을_보내도_원장수량으로_출고한다() {
        // SSOT: 예약은 6이었으므로, 출고 요청이 9로 와도 원장의 6만 차감해야 한다(수량 오염 차단).
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 9));
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(4);   // 10 - 6
        assertThat(after.getReservedQty()).isEqualTo(0); // 6 - 6
    }

    @Test
    void releaseAll_호출자가_잘못된_수량을_보내도_원장수량으로_해제한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.releaseAll(99L, Map.of(1L, 2));
        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getReservedQty()).isEqualTo(0); // 6 - 6 (요청 2가 아님)
        assertThat(after.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void shipAll_원장_상품의_재고행이_사라졌으면_침묵스킵대신_예외() {
        // 예약 후 재고 행이 사라진 비정상 상태 — 예약은 SHIPPED로 넘기고 재고는 안 깎는 침묵 누수를 막는다.
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        repo.deleteAll();

        assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, 6)))
                .isInstanceOf(IllegalStateException.class);
    }
}
