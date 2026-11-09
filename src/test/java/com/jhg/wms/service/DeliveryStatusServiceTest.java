package com.jhg.wms.service;

import com.jhg.wms.client.OmsDeliveryNotifier;
import com.jhg.wms.client.OmsDeliveryNotifier.DeliveryStatusEvent;
import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.DeliveryStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.domain.ReservationStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jhg.wms.domain.DeliveryFailureCode.ADDRESS_ISSUE;
import static com.jhg.wms.domain.DeliveryFailureCode.RECIPIENT_UNAVAILABLE;
import static com.jhg.wms.support.OrderKeys.keyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 배송 실패·재배송·완료의 서비스 계약. 동시성·커밋 경계는 DeliveryConcurrencyTest가 실제 트랜잭션으로 본다. */
@DataJpaTest
class DeliveryStatusServiceTest {

    @Autowired InventoryRepository repo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired JdbcTemplate jdbc;
    InventoryService service;
    OmsDeliveryNotifier deliveryNotifier;

    private static final UUID KEY = keyOf(99L);

    @BeforeEach
    void setUp() throws Exception {
        // create-drop 스키마엔 이전 SQL의 CHECK 5개가 없다 — 앱이 쓰는 값이 실제 제약을 지키는지 여기서 본다.
        // DDL은 이전 SQL의 ④ 블록을 그대로 쓴다(복사본이 갈라지지 않게). 테스트 트랜잭션과 함께 롤백된다.
        String sql = Files.readString(Path.of("docs/wms-delivery-failure-migration.sql"));
        jdbc.execute(sql.substring(sql.indexOf("-- ④"), sql.indexOf("-- ⑤")));
        deliveryNotifier = mock(OmsDeliveryNotifier.class);
        service = new InventoryService(repo, reservationRepo, txnRepo, mock(OmsReplenishmentNotifier.class),
                deliveryNotifier, () -> "operator");
    }

    private void shipped(UUID key, long orderId) {
        if (repo.findByProductId(1L).isEmpty()) repo.save(Inventory.create(1L, 10));
        service.reserveAll(key, orderId, Map.of(1L, 3));
        service.shipAll(key, Map.of(1L, 3));
    }

    private Reservation reload(UUID key) {
        em.flush();
        em.clear();
        return reservationRepo.findByRequestKey(key).orElseThrow();
    }

    /** 송장·재고·원장 — 배송 상태 변경이 절대 건드리면 안 되는 것들. */
    private record Untouched(String trackingNumber, Instant issuedAt, int onHand, int reserved, int available,
                             long shipCount, int shipQty) {}

    private Untouched untouched(UUID key) {
        Reservation r = reload(key);
        Inventory inv = repo.findByProductId(1L).orElseThrow();
        var ships = txnRepo.findAll().stream().filter(t -> t.getType() == InventoryTransactionType.SHIP).toList();
        return new Untouched(r.getTrackingNumber(), r.getIssuedAt(), inv.getOnHandQty(), inv.getReservedQty(),
                inv.getAvailableQty(), ships.size(), ships.stream().mapToInt(t -> t.getDelta()).sum());
    }

    private List<DeliveryStatusEvent> sentEvents() {
        ArgumentCaptor<DeliveryStatusEvent> captor = ArgumentCaptor.forClass(DeliveryStatusEvent.class);
        verify(deliveryNotifier, atLeast(0)).notifyAfterCommit(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void 출고v1_실패v2_재배송v3_완료v4로_정확히_한_칸씩_오르고_송장_재고는_그대로다() {
        shipped(KEY, 99L);
        Reservation v1 = reload(KEY);
        assertThat(v1.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(v1.getDeliveryVersion()).isEqualTo(1);
        assertThat(v1.getDeliveryStatusAt()).isEqualTo(v1.getIssuedAt());
        Untouched before = untouched(KEY);

        assertThat(service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE)).isTrue();
        Reservation v2 = reload(KEY);
        assertThat(v2.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        assertThat(v2.getDeliveryVersion()).isEqualTo(2);
        assertThat(v2.getDeliveryFailureCode()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(v2.getDeliveredAt()).isNull();

        assertThat(service.startRedelivery(KEY)).isTrue();
        Reservation v3 = reload(KEY);
        assertThat(v3.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(v3.getDeliveryVersion()).isEqualTo(3);
        assertThat(v3.getDeliveryFailureCode()).isNull();   // 현재 실패 코드만 둔다

        assertThat(service.markDelivered(KEY)).isTrue();
        Reservation v4 = reload(KEY);
        assertThat(v4.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(v4.getDeliveryVersion()).isEqualTo(4);
        assertThat(v4.getDeliveredAt()).isEqualTo(v4.getDeliveryStatusAt());
        assertThat(v4.getDeliveryFailureCode()).isNull();
        assertThat(v4.getStatus()).isEqualTo(ReservationStatus.SHIPPED);   // 예약 상태는 끝까지 SHIPPED
        assertThat(v4.getRequestKey()).isEqualTo(KEY);

        assertThat(untouched(KEY)).isEqualTo(before);
        assertThat(sentEvents()).extracting(DeliveryStatusEvent::status, DeliveryStatusEvent::deliveryVersion,
                        DeliveryStatusEvent::failureCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DeliveryStatus.DELIVERY_FAILED, 2L, RECIPIENT_UNAVAILABLE),
                        org.assertj.core.groups.Tuple.tuple(DeliveryStatus.SHIPPED, 3L, null),
                        org.assertj.core.groups.Tuple.tuple(DeliveryStatus.DELIVERED, 4L, null));
    }

    @Test
    void 실패_없이_완료하면_v2다() {
        shipped(KEY, 99L);

        service.markDelivered(KEY);

        assertThat(reload(KEY).getDeliveryVersion()).isEqualTo(2);
    }

    @Test
    void 재배송이_또_실패하는_반복도_한_칸씩_오른다() {
        shipped(KEY, 99L);
        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);   // v2
        service.startRedelivery(KEY);                                // v3
        service.recordDeliveryFailure(KEY, ADDRESS_ISSUE);           // v4 — 새 시도의 실패는 다른 사유여도 된다
        service.startRedelivery(KEY);                                // v5
        service.markDelivered(KEY);                                  // v6

        Reservation r = reload(KEY);
        assertThat(r.getDeliveryVersion()).isEqualTo(6);
        assertThat(sentEvents()).extracting(DeliveryStatusEvent::deliveryVersion).containsExactly(2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void 상태별_같은_액션_재요청은_버전_시각을_그대로_두고_같은_스냅샷을_재통지한다() {
        shipped(KEY, 99L);
        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);
        Instant failedAt = reload(KEY).getDeliveryStatusAt();
        assertThat(service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE)).isFalse();
        assertThat(reload(KEY).getDeliveryStatusAt()).isEqualTo(failedAt);

        service.startRedelivery(KEY);
        Instant redeliveredAt = reload(KEY).getDeliveryStatusAt();
        assertThat(service.startRedelivery(KEY)).isFalse();
        assertThat(reload(KEY).getDeliveryStatusAt()).isEqualTo(redeliveredAt);

        service.markDelivered(KEY);
        Instant deliveredAt = reload(KEY).getDeliveryStatusAt();
        assertThat(service.markDelivered(KEY)).isFalse();
        Reservation r = reload(KEY);
        assertThat(r.getDeliveryVersion()).isEqualTo(4);
        assertThat(r.getDeliveryStatusAt()).isEqualTo(deliveredAt);

        List<DeliveryStatusEvent> events = sentEvents();
        assertThat(events).hasSize(6);
        assertThat(events.get(1)).isEqualTo(events.get(0));   // 실패 재통지
        assertThat(events.get(3)).isEqualTo(events.get(2));   // 재배송 재통지
        assertThat(events.get(5)).isEqualTo(events.get(4));   // 완료 재통지
    }

    @Test
    void 실패_중_다른_사유로_바꾸기는_거부하고_아무것도_바꾸지_않는다() {
        shipped(KEY, 99L);
        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);
        Reservation before = reload(KEY);
        clearInvocations(deliveryNotifier);

        assertThatThrownBy(() -> service.recordDeliveryFailure(KEY, ADDRESS_ISSUE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미");

        Reservation after = reload(KEY);
        assertThat(after.getDeliveryFailureCode()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(after.getDeliveryVersion()).isEqualTo(before.getDeliveryVersion());
        verifyNoInteractions(deliveryNotifier);
    }

    @Test
    void 전이표에_없는_요청은_거부하고_상태_통지_재고를_바꾸지_않는다() {
        shipped(KEY, 99L);
        Untouched before = untouched(KEY);

        // 최초 출고 v1에서 재배송
        assertThatThrownBy(() -> service.startRedelivery(KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최초 출고");
        // 실패 중 바로 완료
        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);
        assertThatThrownBy(() -> service.markDelivered(KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재배송");
        assertThat(reload(KEY).getDeliveryVersion()).isEqualTo(2);
        // 완료 후 실패·재배송
        service.startRedelivery(KEY);
        service.markDelivered(KEY);
        clearInvocations(deliveryNotifier);
        assertThatThrownBy(() -> service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.startRedelivery(KEY)).isInstanceOf(IllegalStateException.class);

        Reservation r = reload(KEY);
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(r.getDeliveryVersion()).isEqualTo(4);
        verifyNoInteractions(deliveryNotifier);
        assertThat(untouched(KEY)).isEqualTo(before);
    }

    @Test
    void 없는_예약_미출고_해제_송장없음은_실패_재배송을_거부한다() {
        assertThatThrownBy(() -> service.recordDeliveryFailure(keyOf(404L), RECIPIENT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("예약이 없어");

        repo.save(Inventory.create(1L, 10));
        service.reserveAll(keyOf(1L), 1L, Map.of(1L, 1));                 // 미출고
        assertThatThrownBy(() -> service.startRedelivery(keyOf(1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("출고된 주문만");

        service.reserveAll(keyOf(2L), 2L, Map.of(1L, 1));
        service.releaseAll(keyOf(2L), Map.of(1L, 1));                     // 해제
        assertThatThrownBy(() -> service.recordDeliveryFailure(keyOf(2L), RECIPIENT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("출고된 주문만");

        service.reserveAll(keyOf(3L), 3L, Map.of(1L, 1));
        reservationRepo.findByRequestKey(keyOf(3L)).orElseThrow().ship();  // 송장 이전에 출고된 기존 행
        assertThatThrownBy(() -> service.recordDeliveryFailure(keyOf(3L), RECIPIENT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("송장이 없어");

        verifyNoInteractions(deliveryNotifier);
    }

    @Test
    void 같은_orderId라도_다른_requestKey_예약은_서로_영향이_없다() {
        // OMS DB 초기화로 orderId가 재사용된 상황.
        UUID other = UUID.fromString("11111111-0000-0000-0000-000000000063");
        shipped(KEY, 99L);
        shipped(other, 99L);

        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);

        assertThat(reload(KEY).getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        Reservation untouchedOther = reload(other);
        assertThat(untouchedOther.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(untouchedOther.getDeliveryVersion()).isEqualTo(1);
    }

    @Test
    void 송장을_다시_요청해도_버전은_초기화되지_않는다() {
        shipped(KEY, 99L);
        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);

        service.shipAll(KEY, Map.of(1L, 3));   // OMS 출고 재시도 — 이미 송장이 있으면 아무것도 바꾸지 않는다
        service.findShipment(KEY);

        Reservation r = reload(KEY);
        assertThat(r.getDeliveryVersion()).isEqualTo(2);
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    void 송장_조회는_현재_배송_스냅샷을_준다() {
        shipped(KEY, 99L);
        var v1 = service.findShipment(KEY).orElseThrow();
        assertThat(v1.status()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(v1.deliveryVersion()).isEqualTo(1);
        assertThat(v1.occurredAt()).isEqualTo(v1.issuedAt());
        assertThat(v1.failureCode()).isNull();

        service.recordDeliveryFailure(KEY, RECIPIENT_UNAVAILABLE);
        em.flush();
        em.clear();
        var v2 = service.findShipment(KEY).orElseThrow();
        assertThat(v2.status()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        assertThat(v2.deliveryVersion()).isEqualTo(2);
        assertThat(v2.failureCode()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(v2.deliveredAt()).isNull();
        assertThat(service.findShipment(KEY).orElseThrow()).isEqualTo(v2);   // 조회는 아무것도 바꾸지 않는다
    }

    @Test
    void 이전_SQL_제약이_테스트_스키마에_걸려_있다() {
        shipped(KEY, 99L);
        em.flush();
        // SHIPPED는 홀수 버전만 — 제약이 빠졌다면 이 쓰기가 통과해 위 전이 테스트들이 제약 없이 돈 것이다.
        assertThatThrownBy(() -> jdbc.update("update reservation set delivery_version = 2 where request_key = ?", KEY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
