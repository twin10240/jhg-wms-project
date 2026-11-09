package com.jhg.wms.concurrency;

import com.jhg.wms.domain.DeliveryFailureCode;
import com.jhg.wms.domain.DeliveryStatus;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배송 상태 전이의 동시성. 같은 액션이 겹치면 버전은 한 번만 오르고(나머지는 재통지),
 * 서로 다른 액션이 겹치면 잠금을 먼저 얻은 쪽만 적용되고 뒤쪽은 그 결과 상태에서 다시 판정된다.
 * 통지 대상은 듣는 이가 없는 포트다 — 커밋 후 전송 실패가 상태를 되돌리지 않는 것도 함께 본다.
 */
@DisplayName("배송 상태 전이의 동시성")
@TestPropertySource(properties = "oms.base-url=http://localhost:1")
class DeliveryConcurrencyTest extends ConcurrencySupport {

    @Autowired InventoryService inventoryService;
    @Autowired ReservationRepository reservationRepository;

    private UUID shipped(long n) {
        long pid = PID_BASE + n;
        long orderId = ORDER_BASE + n;
        UUID key = new UUID(0, orderId);
        seedInventory(pid, 10);
        inventoryService.reserveAll(key, orderId, Map.of(pid, 3));
        inventoryService.shipAll(key, Map.of(pid, 3));
        return key;
    }

    private Reservation reservation(UUID key) {
        return tx.execute(s -> reservationRepository.findByRequestKey(key).orElseThrow());
    }

    @Test
    void 같은_실패를_동시에_다섯_번_보내도_버전은_한_번만_오른다() {
        UUID key = shipped(200);
        int onHand = onHandOf(PID_BASE + 200);

        RaceResult result = race(5, i -> {
            inventoryService.recordDeliveryFailure(key, DeliveryFailureCode.RECIPIENT_UNAVAILABLE);
            return true;
        });

        assertThat(result.succeeded()).as("뒤 요청은 거부가 아니라 재통지여야 한다").isEqualTo(5);
        Reservation r = reservation(key);
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        assertThat(r.getDeliveryVersion()).isEqualTo(2);
        assertThat(onHandOf(PID_BASE + 200)).isEqualTo(onHand);
    }

    @Test
    void 같은_재배송과_같은_완료도_동시에_와도_한_번만_오른다() {
        UUID key = shipped(201);
        inventoryService.recordDeliveryFailure(key, DeliveryFailureCode.ADDRESS_ISSUE);

        RaceResult redeliver = race(5, i -> { inventoryService.startRedelivery(key); return true; });
        assertThat(redeliver.succeeded()).isEqualTo(5);
        assertThat(reservation(key).getDeliveryVersion()).isEqualTo(3);

        RaceResult deliver = race(5, i -> { inventoryService.markDelivered(key); return true; });
        assertThat(deliver.succeeded()).isEqualTo(5);
        Reservation r = reservation(key);
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(r.getDeliveryVersion()).isEqualTo(4);
    }

    @Test
    void 실패와_완료가_겹치면_먼저_잠근_쪽만_적용되고_뒤쪽은_거부된다() {
        UUID key = shipped(202);

        RaceResult result = race(2, i -> {
            if (i == 0) inventoryService.recordDeliveryFailure(key, DeliveryFailureCode.RECIPIENT_UNAVAILABLE);
            else inventoryService.markDelivered(key);
            return true;
        });

        // SHIPPED v1에서 둘 다 유효하지만, 먼저 커밋된 결과 위에서는 서로 전이표에 없다(실패→완료, 완료→실패).
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.errors()).singleElement().isInstanceOf(IllegalStateException.class);
        Reservation r = reservation(key);
        assertThat(r.getDeliveryVersion()).isEqualTo(2);
        assertThat(r.getDeliveryStatus()).isIn(DeliveryStatus.DELIVERY_FAILED, DeliveryStatus.DELIVERED);
        assertThat(r.getDeliveredAt() != null).isEqualTo(r.getDeliveryStatus() == DeliveryStatus.DELIVERED);
    }
}
