package com.jhg.wms.service;

import com.jhg.wms.client.OmsDeliveryNotifier;
import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.domain.ReservationStatus;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.web.ShipResponse;
import com.jhg.wms.web.ShipmentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @Autowired jakarta.persistence.EntityManager em;
    InventoryService service;
    OmsReplenishmentNotifier notifier;
    OmsDeliveryNotifier deliveryNotifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        deliveryNotifier = mock(OmsDeliveryNotifier.class);
        service = new InventoryService(repo, reservationRepo, adjustmentRepo, notifier,
                deliveryNotifier, () -> "manager");
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

    // ── orderId 재사용 방어(OMS·WMS DB 개별 초기화) ────────────────────

    @Test
    void adjust_사유가_비면_거부하고_재고를_바꾸지_않는다() {
        // OPERATOR도 조정 가능해 통제가 사후 추적뿐 — 사유 없는 조정은 추적을 무력화한다.
        seed(1L, 10);

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.adjust(1L, -3, blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("사유는 필수");
        }

        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()).isEqualTo(10);
        assertThat(adjustmentRepo.count()).isZero();
    }

    @Test
    void reserveAll_같은_orderId_다른_상품이면_409용_예외를_던지고_불변이다() {
        // OMS 주문 52는 상품1×1·상품2×2인데 WMS에는 상품2×1짜리 과거 예약 52가 남은 상황.
        seed(1L, 10); seed(2L, 10);
        service.reserveAll(52L, Map.of(2L, 1));

        assertThatThrownBy(() -> service.reserveAll(52L, Map.of(1L, 1, 2L, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 원장 불일치")
                .hasMessageContaining("orderId=52");

        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(0);
        assertThat(repo.findByProductIdIn(List.of(2L)).get(0).getReservedQty()).isEqualTo(1);
        assertThat(reservationRepo.findByOrderId(52L).orElseThrow().getQtyByProductId())
                .isEqualTo(Map.of(2L, 1));
    }

    @Test
    void reserveAll_같은_orderId_같은_상품_다른_수량이면_예외를_던지고_불변이다() {
        seed(1L, 10);
        service.reserveAll(52L, Map.of(1L, 1));

        assertThatThrownBy(() -> service.reserveAll(52L, Map.of(1L, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 원장 불일치");

        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(1);
    }

    @Test
    void reserveAll_기존이_SHIPPED여도_품목이_다르면_예외다() {
        seed(1L, 10); seed(2L, 10);
        service.reserveAll(52L, Map.of(2L, 1));
        service.shipAll(52L, Map.of(2L, 1));

        assertThatThrownBy(() -> service.reserveAll(52L, Map.of(1L, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 원장 불일치");

        Inventory p2 = repo.findByProductIdIn(List.of(2L)).get(0);
        assertThat(p2.getOnHandQty()).isEqualTo(9);
        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(0);
    }

    @Test
    void reserveAll_기존이_RELEASED여도_품목이_다르면_예외다() {
        seed(1L, 10); seed(2L, 10);
        service.reserveAll(52L, Map.of(2L, 1));
        service.releaseAll(52L, Map.of(2L, 1));

        assertThatThrownBy(() -> service.reserveAll(52L, Map.of(1L, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 원장 불일치");

        assertThat(repo.findByProductIdIn(List.of(1L)).get(0).getReservedQty()).isEqualTo(0);
        assertThat(repo.findByProductIdIn(List.of(2L)).get(0).getReservedQty()).isEqualTo(0);
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

    // ── 송장 조회 ──────────────────────────────────────────────

    @Test
    void findShipment_발급된_송장과_배송상태를_돌려준다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        ShipResponse shipped = service.shipAll(99L, Map.of(1L, 6));

        ShipmentResponse inTransit = service.findShipment(99L).orElseThrow();
        assertThat(inTransit.orderId()).isEqualTo(99L);
        assertThat(inTransit.carrierCode()).isEqualTo("MOCK");
        assertThat(inTransit.carrierName()).isEqualTo("테스트택배");
        assertThat(inTransit.trackingNumber()).isEqualTo(shipped.trackingNumber());
        assertThat(inTransit.issuedAt()).isEqualTo(shipped.issuedAt());
        assertThat(inTransit.deliveredAt()).isNull();          // 배송 중

        service.markDelivered(99L);
        assertThat(service.findShipment(99L).orElseThrow().deliveredAt())
                .isEqualTo(reservationRepo.findByOrderId(99L).orElseThrow().getDeliveredAt());
    }

    @Test
    void findShipment_예약이_없거나_송장이_미발급이면_비어있다() {
        assertThat(service.findShipment(404L)).isEmpty();       // 예약 없음

        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));                 // 출고 전 = 송장 미발급
        assertThat(service.findShipment(99L)).isEmpty();
    }

    @Test
    void findShipment_여러_번_조회해도_아무것도_바꾸지_않는다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        service.markDelivered(99L);
        long txnsBefore = adjustmentRepo.count();

        ShipmentResponse first = service.findShipment(99L).orElseThrow();
        service.findShipment(99L);
        ShipmentResponse third = service.findShipment(99L).orElseThrow();

        assertThat(third).isEqualTo(first);                     // 송장·발급시각·배송시각 그대로
        Inventory inv = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(inv.getOnHandQty()).isEqualTo(4);
        assertThat(inv.getReservedQty()).isEqualTo(0);
        assertThat(reservationRepo.findByOrderId(99L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.SHIPPED);
        assertThat(adjustmentRepo.count()).isEqualTo(txnsBefore);
    }

    // ── 배송 완료 ──────────────────────────────────────────────

    @Test
    void markDelivered_시각을_남기고_OMS에_통지하되_재고는_건드리지_않는다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        long txnsBefore = adjustmentRepo.count();

        assertThat(service.markDelivered(99L)).isTrue();   // 최초 기록

        Reservation r = reservationRepo.findByOrderId(99L).orElseThrow();
        assertThat(r.getDeliveredAt()).isNotNull();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);   // 상태는 그대로 — 세 군데 SHIPPED 분기를 안 건드린다
        verify(deliveryNotifier).notifyAfterCommit(99L, r.getDeliveredAt());

        Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
        assertThat(after.getOnHandQty()).isEqualTo(4);                    // 출고 때 깎인 그대로
        assertThat(after.getReservedQty()).isEqualTo(0);
        assertThat(adjustmentRepo.count()).isEqualTo(txnsBefore);         // 원장 사건이 아니다
    }

    @Test
    void markDelivered_재호출은_시각을_덮어쓰지_않고_통지만_재발송한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        service.markDelivered(99L);
        Instant first = reservationRepo.findByOrderId(99L).orElseThrow().getDeliveredAt();

        assertThat(service.markDelivered(99L)).isFalse();   // 통지만 재발송

        assertThat(reservationRepo.findByOrderId(99L).orElseThrow().getDeliveredAt()).isEqualTo(first);
        verify(deliveryNotifier, times(2)).notifyAfterCommit(99L, first);  // 통지 유실 시 재클릭이 복구 경로
    }

    @Test
    void markDelivered_출고전_예약은_거부한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));

        assertThatThrownBy(() -> service.markDelivered(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("출고된 주문만");
        verifyNoInteractions(deliveryNotifier);
    }

    @Test
    void markDelivered_송장이_없으면_거부한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        // 송장 발급 이전에 출고된 기존 주문 재현 — shipAll을 거치지 않고 상태만 SHIPPED로 만든다.
        Reservation r = reservationRepo.findByOrderId(99L).orElseThrow();
        r.ship();
        em.flush();

        assertThatThrownBy(() -> service.markDelivered(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("송장이 없어");
        verifyNoInteractions(deliveryNotifier);
    }

    @Test
    void markDelivered_예약이_없으면_거부한다() {
        assertThatThrownBy(() -> service.markDelivered(404L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약이 없어");
        verifyNoInteractions(deliveryNotifier);
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

    // ── Task 4: 출고 → SHIP 기록 ────────────────────────────────

    @Test
    void shipAll_출고하면_SHIP_트랜잭션이_상품당_남는다() {
        seed(1L, 10); seed(2L, 5);
        service.reserveAll(77L, Map.of(1L, 3, 2L, 2));
        service.shipAll(77L, Map.of(1L, 3, 2L, 2));
        var ships = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == com.jhg.wms.domain.InventoryTransactionType.SHIP).toList();
        assertThat(ships).hasSize(2);
        assertThat(ships).allSatisfy(t -> assertThat(t.getReference()).isEqualTo("ORDER#77"));
        assertThat(ships.stream().mapToInt(t -> t.getDelta()).sum()).isEqualTo(-5); // -3 + -2
    }

    // ── Task 6: 재구성 불변식(Σdelta==onHand) ────────────────────

    @Test
    void 원장_델타합이_현재_onHand와_같다() {
        seed(1L, 0);
        service.applyDelta(1L, 100, InventoryTransactionType.OPENING, null, null); // 100
        service.applyDelta(1L, 50, InventoryTransactionType.RECEIVE, "PO#1", null); // 150
        service.reserveAll(10L, Map.of(1L, 30));
        service.shipAll(10L, Map.of(1L, 30));                                       // 120
        service.adjust(1L, -5, "파손");                                            // 115

        int deltaSum = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getProductId() == 1L)
                .mapToInt(t -> t.getDelta()).sum();
        int onHand = repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty();
        assertThat(deltaSum).isEqualTo(onHand);   // 115
        assertThat(onHand).isEqualTo(115);
    }

    // ── 수불대장 ──────────────────────────────────────────────

    /** 이미 쌓인 원장을 통째로 과거로 민다 — createdAt이 now() 고정이라 기간 경계를 이렇게만 만들 수 있다. */
    private void 기존원장을_과거로(java.time.LocalDateTime when) {
        em.flush();
        em.createQuery("update InventoryTransaction t set t.createdAt = :when")
                .setParameter("when", when).executeUpdate();
        em.clear();
    }

    @Test
    void buildLedger_기초는_기간이전_누적이고_기말은_항등식을_만족한다() {
        seed(1L, 0);
        service.applyDelta(1L, 100, InventoryTransactionType.OPENING, null, null);
        service.applyDelta(1L, -10, InventoryTransactionType.SHIP, "ORDER#0", null);
        기존원장을_과거로(java.time.LocalDateTime.now().minusDays(10));   // 여기까지가 기초 90

        service.applyDelta(1L, 50, InventoryTransactionType.RECEIVE, "PO#1", null);
        service.applyDelta(1L, 5, InventoryTransactionType.RETURN, "RMA#1", null);
        service.applyDelta(1L, -20, InventoryTransactionType.SHIP, "ORDER#1", null);
        service.applyDelta(1L, -3, InventoryTransactionType.ADJUST, null, "파손");
        service.applyDelta(1L, 2, InventoryTransactionType.COUNT, "COUNT#1", null);   // 실사 차이 반영

        var rows = service.buildLedger(java.time.LocalDate.now(), java.time.LocalDate.now());

        assertThat(rows).hasSize(1);
        var r = rows.get(0);
        assertThat(r.opening()).isEqualTo(90);     // 기간 이전분만
        assertThat(r.initial()).isZero();
        assertThat(r.receive()).isEqualTo(50);
        assertThat(r.returnQty()).isEqualTo(5);
        assertThat(r.ship()).isEqualTo(-20);       // 기간 이전 -10은 제외
        assertThat(r.adjust()).isEqualTo(-3);
        assertThat(r.countQty()).isEqualTo(2);
        assertThat(r.closing()).isEqualTo(r.opening() + r.initial() + r.receive() + r.returnQty()
                + r.ship() + r.adjust() + r.countQty());
        assertThat(r.closing()).isEqualTo(repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty()); // 124
    }

    @Test
    void buildLedger_기간내_OPENING은_조정이_아니라_기초설정_열로_간다() {
        seed(1L, 0);
        service.applyDelta(1L, 100, InventoryTransactionType.OPENING, null, null);
        service.applyDelta(1L, -3, InventoryTransactionType.ADJUST, null, "파손");

        var r = service.buildLedger(java.time.LocalDate.now(), java.time.LocalDate.now()).get(0);

        assertThat(r.opening()).isZero();
        assertThat(r.initial()).isEqualTo(100);
        assertThat(r.adjust()).isEqualTo(-3);
        assertThat(r.closing()).isEqualTo(97);
    }

    @Test
    void buildLedger_원장에_없는_상품은_행으로_나오지_않는다() {
        seed(1L, 0);
        seed(2L, 0);   // 변동 없음 — 재고 행만 존재
        service.applyDelta(1L, 10, InventoryTransactionType.RECEIVE, "PO#1", null);

        var rows = service.buildLedger(java.time.LocalDate.now(), java.time.LocalDate.now());

        assertThat(rows).extracting(InventoryService.LedgerRow::productId).containsExactly(1L);
    }

    @Test
    void buildLedger_원장이_비면_빈_목록() {
        seed(1L, 0);
        assertThat(service.buildLedger(java.time.LocalDate.now(), java.time.LocalDate.now())).isEmpty();
    }

    @Test
    void 불변식검사는_원장이_통째로_누락된_재고도_위반으로_잡는다() {
        seed(1L, 7);

        var violations = service.findInvariantViolations(List.of());

        assertThat(violations).containsExactly(
                new InventoryService.InvariantViolation(1L, null, 0, 7));
    }

    @Test
    void buildLedger_시작일이_종료일보다_뒤면_예외() {
        assertThatThrownBy(() -> service.buildLedger(
                java.time.LocalDate.now(), java.time.LocalDate.now().minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작일");
    }

    // 접근제어를 롤로 나눠뒀는데 원장에 누가 했는지가 없으면 감사 질문에 답할 수 없다.
    @Test
    void applyDelta는_행위자를_원장에_남긴다() {
        seed(1L, 10);

        service.applyDelta(1L, 5, InventoryTransactionType.RECEIVE, "PO#1", null);

        assertThat(adjustmentRepo.findAll())
                .extracting(com.jhg.wms.domain.InventoryTransaction::getActor)
                .containsExactly("manager");
    }

    @Test
    void 출고도_행위자를_남긴다() {
        seed(1L, 10);
        service.reserveAll(1L, Map.of(1L, 3));
        service.shipAll(1L, Map.of(1L, 3));

        assertThat(adjustmentRepo.findAll())
                .filteredOn(t -> t.getType() == InventoryTransactionType.SHIP)
                .extracting(com.jhg.wms.domain.InventoryTransaction::getActor)
                .containsExactly("manager");
    }

    @Test
    void shipAll_출고하면_MOCK_송장을_발급한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));

        ShipResponse res = service.shipAll(99L, Map.of(1L, 6));

        assertThat(res.orderId()).isEqualTo(99L);
        assertThat(res.carrierCode()).isEqualTo("MOCK");
        assertThat(res.carrierName()).isEqualTo("테스트택배");
        assertThat(res.trackingNumber()).matches("MOCK-99-\\d{14}");
        assertThat(res.issuedAt()).isNotNull();
    }

    @Test
    void shipAll_재호출해도_같은_송장을_주고_재고는_한_번만_줄어든다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));

        ShipResponse first = service.shipAll(99L, Map.of(1L, 6));
        ShipResponse again = service.shipAll(99L, Map.of(1L, 6));

        assertThat(again.trackingNumber()).isEqualTo(first.trackingNumber());
        assertThat(again.issuedAt()).isEqualTo(first.issuedAt());
        assertThat(repo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(4);
    }

    @Test
    void shipAll_재고행이_없어_실패하면_송장도_생기지_않는다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        // 예약 후 재고 행이 사라진 상태 — 출고 루프가 예외를 던지고 송장 발급에 도달하지 못한다.
        repo.delete(repo.findByProductId(1L).orElseThrow());
        em.flush();

        assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, 6)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservationRepo.findByOrderId(99L).orElseThrow().getTrackingNumber()).isNull();
    }

    @Test
    void shipAll_이미_출고됐지만_송장이_없으면_송장만_보완한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        // 이 기능 이전에 출고된 주문 재현 — 송장 세 필드를 모두 비운다.
        em.createQuery("UPDATE Reservation r SET r.trackingNumber = null, r.carrierCode = null, "
                + "r.issuedAt = null WHERE r.orderId = 99").executeUpdate();
        em.clear();

        ShipResponse res = service.shipAll(99L, Map.of(1L, 6));

        assertThat(res.trackingNumber()).matches("MOCK-99-\\d{14}");
        assertThat(repo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(4);   // 재고 불변
    }

    // ── 드릴다운 조회 ────────────────────────────────────────────

    /** createdAt은 of()가 now()로 박으므로, 기간 경계를 시험하려면 심어서 넣어야 한다. */
    private void seedTxnAt(Long productId, InventoryTransactionType type, int delta, LocalDateTime at) {
        var txn = InventoryTransaction.of(productId, type, delta, 0, delta, null, null, "test");
        ReflectionTestUtils.setField(txn, "createdAt", at);
        adjustmentRepo.save(txn);
    }

    @Test
    void 상품으로_좁히면_다른_상품은_안_나온다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));

        var page = service.findTransactions(null, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getProductId)
                .containsOnly(1L);
    }

    // 반개구간 [from 00:00, to+1일 00:00). buildLedger와 같은 경계여야 수불대장과 상세가 맞는다.
    @Test
    void 종료일_당일은_포함하고_다음날은_제외한다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 1, LocalDateTime.of(2026, 9, 30, 23, 59));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 2, LocalDateTime.of(2026, 10, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 3, LocalDateTime.of(2026, 8, 31, 23, 59));

        var page = service.findTransactions(null, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getDelta)
                .containsExactlyInAnyOrder(1);
    }

    @Test
    void 유형과_상품과_기간을_함께_건다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -4, LocalDateTime.of(2026, 9, 5, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.SHIP, -7, LocalDateTime.of(2026, 9, 5, 10, 0));

        var page = service.findTransactions(InventoryTransactionType.SHIP, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getDelta)
                .containsExactly(-4);
    }

    // 범위를 안 걸면 전건이 나와야 한다 — 날짜를 넓은 경계로 대체하는 처리가 조용히 걸러내면 안 된다.
    // 코드가 쓰는 경계는 1970/9999다. 2020/2030처럼 그보다 훨씬 좁은 값으로 심으면, 이 테스트는
    // NO_LOWER_BOUND/NO_UPPER_BOUND가 세기 단위로 넓다는 사실이 아니라 "웬만큼 넓다"만 확인하게
    // 되어 그 상수가 실수로 좁아져도 못 잡는다. 1971/2999로 그 상수 자체를 겨눈다.
    @Test
    void 범위를_안_걸면_전건이_나온다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(1971, 1, 1, 0, 0));
        seedTxnAt(2L, InventoryTransactionType.SHIP, -4, LocalDateTime.of(2999, 12, 31, 0, 0));

        var page = service.findTransactions(null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
    }

    // search(...)는 이 리포지토리에서 @Query + Pageable을 쓰는 첫 메서드다 — 나머지 페이징
    // 메서드는 전부 파생 쿼리라 Spring Data가 카운트 쿼리를 알아서 만든다. @Query를 쓰면
    // Spring Data가 JPQL에서 카운트 쿼리를 유도해야 하는데, 지금까지 모든 픽스처가 한 페이지
    // 안에 들어가 PageableExecutionUtils가 카운트 실행 자체를 건너뛰어 왔다 — 21건을 심어
    // 두 페이지로 나눠 그 카운트 쿼리를 실제 PostgreSQL에 대해 처음 실행시킨다.
    @Test
    void 페이지를_넘는_건수는_카운트_쿼리로_totalPages를_계산한다() {
        for (int i = 0; i < 21; i++)
            seedTxnAt(1L, InventoryTransactionType.RECEIVE, 1, LocalDateTime.of(2026, 9, 3, 10, i));

        var page = service.findTransactions(null, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void 한_상품의_수불행을_꺼낸다() {
        seedTxnAt(1L, InventoryTransactionType.OPENING, 100, LocalDateTime.of(2026, 8, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -15, LocalDateTime.of(2026, 9, 11, 10, 0));

        var row = service.ledgerRowOf(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                .orElseThrow();

        assertThat(row.opening()).isEqualTo(100);
        assertThat(row.closing()).isEqualTo(105);
    }

    @Test
    void 트랜잭션이_전혀_없는_상품은_수불행이_없다() {
        assertThat(service.ledgerRowOf(999L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
    }

    // 이 기능이 성립한다는 것의 정의: 대조 줄의 '이동'과 같은 범위 트랜잭션의 변동 합이 같다.
    // 나머지가 다 통과해도 이게 깨지면 드릴다운이 요약과 다른 이야기를 하는 것이다.
    @Test
    void 대조줄의_이동과_범위_트랜잭션_변동합이_같다() {
        seedTxnAt(1L, InventoryTransactionType.OPENING, 100, LocalDateTime.of(2026, 8, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -15, LocalDateTime.of(2026, 9, 11, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.RETURN, 3, LocalDateTime.of(2026, 9, 20, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 99, LocalDateTime.of(2026, 10, 5, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.RECEIVE, 77, LocalDateTime.of(2026, 9, 5, 10, 0));

        LocalDate from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30);
        var row = service.ledgerRowOf(1L, from, to).orElseThrow();
        int deltaSum = service.findTransactions(null, 1L, from, to, PageRequest.of(0, 100))
                .getContent().stream().mapToInt(InventoryTransaction::getDelta).sum();

        assertThat(row.closing() - row.opening()).isEqualTo(deltaSum);
        assertThat(deltaSum).isEqualTo(8);
        assertThat(row.opening()).isEqualTo(100);
        assertThat(row.closing()).isEqualTo(108);
    }
}
