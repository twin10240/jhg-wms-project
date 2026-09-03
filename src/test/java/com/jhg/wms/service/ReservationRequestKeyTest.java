package com.jhg.wms.service;

import com.jhg.wms.client.OmsDeliveryNotifier;
import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.web.ShipResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 연동 식별자를 orderId(숫자 시퀀스)에서 requestKey(OMS가 만든 UUID)로 옮긴 이유를 고정한다.
 *
 * <p>OMS DB가 초기화되면 orderId 시퀀스가 1부터 다시 발급된다. 그때 WMS에 남아 있는 옛 예약과
 * 신규 주문이 같은 번호를 갖게 되는데, 품목·수량까지 우연히 같으면(로컬에서 같은 테스트 주문을
 * 재실행하면 흔하다) 옛 코드는 409조차 내지 않고 조용히 옛 예약을 신규 주문의 것으로 반환했다.
 * 그 결과 재고는 예약되지 않은 채 OMS만 성공으로 믿었고, 출고는 옛 주문의 송장을 돌려줬다.
 */
@DataJpaTest
class ReservationRequestKeyTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository transactionRepo;
    InventoryService service;

    private static final long REUSED_ORDER_ID = 352L;

    @BeforeEach
    void setUp() {
        service = new InventoryService(inventoryRepo, reservationRepo, transactionRepo,
                mock(OmsReplenishmentNotifier.class), mock(OmsDeliveryNotifier.class), () -> "manager");
    }

    private void seed(long productId, int qty) {
        inventoryRepo.save(Inventory.create(productId, qty));
    }

    private int availableOf(long productId) {
        return inventoryRepo.findByProductId(productId).orElseThrow().getAvailableQty();
    }

    @Test
    void 재사용된_orderId라도_requestKey가_다르면_신규주문분이_실제로_예약된다() {
        seed(1L, 10);
        UUID previousGeneration = UUID.randomUUID();
        UUID currentGeneration = UUID.randomUUID();

        // 옛 세대: 주문 352를 예약하고 출고까지 끝냈다. onHand 10-3=7, reserved 0 → available 7
        service.reserveAll(previousGeneration, REUSED_ORDER_ID, Map.of(1L, 3));
        service.shipAll(previousGeneration, Map.of(1L, 3));
        assertThat(availableOf(1L)).isEqualTo(7);

        // OMS DB 초기화 후 같은 번호·같은 품목으로 다시 만들어진 주문
        boolean reserved = service.reserveAll(currentGeneration, REUSED_ORDER_ID, Map.of(1L, 3));

        assertThat(reserved).isTrue();
        // 옛 예약을 재사용했다면 available은 7 그대로다 — 신규 3개가 실제로 잡혀야 4가 된다.
        assertThat(availableOf(1L)).isEqualTo(4);
        assertThat(reservationRepo.count()).isEqualTo(2);
    }

    @Test
    void 재사용된_orderId의_신규주문은_옛_송장이_아니라_자기_송장을_받는다() {
        seed(1L, 10);
        UUID previousGeneration = UUID.randomUUID();
        UUID currentGeneration = UUID.randomUUID();

        service.reserveAll(previousGeneration, REUSED_ORDER_ID, Map.of(1L, 3));
        ShipResponse previous = service.shipAll(previousGeneration, Map.of(1L, 3));

        service.reserveAll(currentGeneration, REUSED_ORDER_ID, Map.of(1L, 3));
        ShipResponse current = service.shipAll(currentGeneration, Map.of(1L, 3));

        assertThat(current.trackingNumber()).isNotEqualTo(previous.trackingNumber());
        assertThat(current.requestKey()).isEqualTo(currentGeneration);
        // 두 번째 출고도 실물을 깎아야 한다: 10 - 3 - 3 = 4
        assertThat(availableOf(1L)).isEqualTo(4);
    }

    @Test
    void 같은requestKey_같은품목_재요청은_멱등이다() {
        seed(1L, 10);
        UUID key = UUID.randomUUID();

        assertThat(service.reserveAll(key, 700L, Map.of(1L, 3))).isTrue();
        assertThat(service.reserveAll(key, 700L, Map.of(1L, 3))).isTrue();

        assertThat(availableOf(1L)).isEqualTo(7);   // 두 번 깎이지 않는다
        assertThat(reservationRepo.count()).isEqualTo(1);
    }

    @Test
    void 같은requestKey인데_품목이_다르면_거부한다() {
        seed(1L, 10);
        seed(2L, 10);
        UUID key = UUID.randomUUID();
        service.reserveAll(key, 700L, Map.of(1L, 3));

        assertThatThrownBy(() -> service.reserveAll(key, 700L, Map.of(2L, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("원장 불일치");
    }

    @Test
    void requestKey가_없으면_400이다() {
        seed(1L, 10);

        assertThatThrownBy(() -> service.reserveAll(null, 700L, Map.of(1L, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestKey");
    }

    @Test
    void 송장조회는_requestKey로_해당_세대의_예약만_찾는다() {
        seed(1L, 10);
        UUID previousGeneration = UUID.randomUUID();
        UUID currentGeneration = UUID.randomUUID();

        service.reserveAll(previousGeneration, REUSED_ORDER_ID, Map.of(1L, 3));
        service.shipAll(previousGeneration, Map.of(1L, 3));
        service.reserveAll(currentGeneration, REUSED_ORDER_ID, Map.of(1L, 3));
        service.shipAll(currentGeneration, Map.of(1L, 3));

        assertThat(service.findShipment(previousGeneration).orElseThrow().trackingNumber())
                .isNotEqualTo(service.findShipment(currentGeneration).orElseThrow().trackingNumber());
    }
}
