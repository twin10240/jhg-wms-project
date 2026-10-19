package com.jhg.wms.client;

import com.jhg.wms.client.OmsDeliveryNotifier.DeliveryStatusEvent;
import com.jhg.wms.domain.DeliveryFailureCode;
import com.jhg.wms.domain.DeliveryStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 콜백과 커밋 경계. 실제 트랜잭션이 필요해 @SpringBootTest다(@DataJpaTest는 커밋하지 않는다).
 * send가 패키지 비공개라 검증을 위해 client 패키지에 둔다. 통지 대상은 듣는 이가 없는 포트다.
 */
@SpringBootTest
@TestPropertySource(properties = "oms.base-url=http://localhost:1")
class DeliveryCallbackCommitTest {

    private static final long PID = 9600L;
    private static final long ORDER_ID = 9600L;
    private static final UUID KEY = new UUID(0, ORDER_ID);

    @MockitoSpyBean OmsDeliveryNotifier notifier;
    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired com.jhg.wms.repository.InventoryTransactionRepository transactionRepository;
    @Autowired TransactionTemplate tx;

    private void shipped() {
        tx.executeWithoutResult(s -> inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));
        inventoryService.reserveAll(KEY, ORDER_ID, Map.of(PID, 2));
        inventoryService.shipAll(KEY, Map.of(PID, 2));
    }

    private Reservation reservation() {
        return tx.execute(s -> reservationRepository.findByRequestKey(KEY).orElseThrow());
    }

    @Test
    void 롤백되면_상태도_버전도_통지도_남지_않는다() {
        shipped();

        tx.executeWithoutResult(s -> {
            inventoryService.recordDeliveryFailure(KEY, DeliveryFailureCode.RECIPIENT_UNAVAILABLE);
            s.setRollbackOnly();   // 같은 트랜잭션의 다른 작업이 실패한 상황
        });

        Reservation r = reservation();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(r.getDeliveryVersion()).isEqualTo(1);
        verify(notifier, never()).send(any());
    }

    @Test
    void 커밋_뒤에만_보내고_전송이_실패해도_상태는_남는다() {
        shipped();

        inventoryService.recordDeliveryFailure(KEY, DeliveryFailureCode.RECIPIENT_UNAVAILABLE);

        ArgumentCaptor<DeliveryStatusEvent> sent = ArgumentCaptor.forClass(DeliveryStatusEvent.class);
        verify(notifier).send(sent.capture());
        Reservation r = reservation();
        // localhost:1은 연결 거부 — 그래도 실패 기록은 커밋돼 있어야 같은 버튼 재통지로 복구된다.
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        assertThat(sent.getValue()).isEqualTo(DeliveryStatusEvent.snapshotOf(r));
    }

    @Test
    void 보낸_스냅샷은_뒤이은_상태_변경에_바뀌지_않는다() {
        shipped();

        // 한 트랜잭션에서 실패 → 재배송을 이어 하면, 커밋 시점 엔티티는 v3이지만 첫 통지는 v2여야 한다.
        tx.executeWithoutResult(s -> {
            inventoryService.recordDeliveryFailure(KEY, DeliveryFailureCode.ADDRESS_ISSUE);
            inventoryService.startRedelivery(KEY);
        });

        ArgumentCaptor<DeliveryStatusEvent> sent = ArgumentCaptor.forClass(DeliveryStatusEvent.class);
        verify(notifier, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(DeliveryStatusEvent::status, DeliveryStatusEvent::deliveryVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DeliveryStatus.DELIVERY_FAILED, 2L),
                        org.assertj.core.groups.Tuple.tuple(DeliveryStatus.SHIPPED, 3L));
        assertThat(sent.getAllValues().get(0).failureCode()).isEqualTo(DeliveryFailureCode.ADDRESS_ISSUE);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> {
            reservationRepository.findByRequestKey(KEY).ifPresent(reservationRepository::delete);
            inventoryRepository.findByProductId(PID).ifPresent(inventoryRepository::delete);
            // shipAll이 남긴 SHIP 원장행 — 재고행 없이 남으면 수불대장 집계에 유령 상품이 생긴다.
            transactionRepository.findAll().stream().filter(t -> t.getProductId() == PID)
                    .forEach(transactionRepository::delete);
        });
    }
}
