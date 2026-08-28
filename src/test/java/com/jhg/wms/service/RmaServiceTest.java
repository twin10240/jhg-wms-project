package com.jhg.wms.service;

import com.jhg.wms.client.OmsReturnStatusNotifier;
import com.jhg.wms.domain.*;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.repository.RmaReturnRepository;
import com.jhg.wms.web.CreateRmaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
class RmaServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired RmaReturnRepository rmaRepo;
    InventoryService inventoryService;
    RmaService rmaService;
    OmsReturnStatusNotifier returnNotifier;

    @BeforeEach
    void setUp() {
        var replenishNotifier = mock(com.jhg.wms.client.OmsReplenishmentNotifier.class);
        returnNotifier = mock(OmsReturnStatusNotifier.class);
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, txnRepo, replenishNotifier,
                mock(com.jhg.wms.client.OmsDeliveryNotifier.class), () -> "system");
        rmaService = new RmaService(rmaRepo, reservationRepo, inventoryService, returnNotifier);
    }

    private void seedAndShip(long orderId, Map<Long, Integer> items) {
        items.forEach((pid, qty) -> {
            if (inventoryRepo.findByProductId(pid).isEmpty())
                inventoryRepo.save(Inventory.create(pid, qty + 10));
        });
        inventoryService.reserveAll(orderId, items);
        inventoryService.shipAll(orderId, items);
    }

    private CreateRmaRequest req(String key, long orderId, String reason,
                                  List<CreateRmaRequest.Item> items) {
        return new CreateRmaRequest(key, orderId, reason, items);
    }

    private List<CreateRmaRequest.Item> items(long orderItemId, long productId, int qty) {
        return List.of(new CreateRmaRequest.Item(orderItemId, productId, qty));
    }

    // ── 접수 ──────────────────────────────────────────────────────

    @Test
    void 접수_정상_생성() {
        seedAndShip(100L, Map.of(1L, 5));
        String key = UUID.randomUUID().toString();
        var result = rmaService.createReturn(req(key, 100L, "불량", items(501, 1, 2)));
        assertThat(result.created()).isTrue();
        assertThat(result.rma().getId()).isNotNull();
        assertThat(result.rma().getStatus()).isEqualTo(RmaStatus.REQUESTED);
        assertThat(result.rma().getItems()).hasSize(1);
    }

    @Test
    void 같은_requestKey_같은_내용이면_기존_반환() {
        seedAndShip(100L, Map.of(1L, 5));
        String key = UUID.randomUUID().toString();
        var first = rmaService.createReturn(req(key, 100L, "불량", items(501, 1, 2)));
        var second = rmaService.createReturn(req(key, 100L, "불량", items(501, 1, 2)));
        assertThat(second.created()).isFalse();
        assertThat(second.rma().getId()).isEqualTo(first.rma().getId());
    }

    @Test
    void 같은_requestKey_다른_내용이면_409() {
        seedAndShip(100L, Map.of(1L, 5));
        String key = UUID.randomUUID().toString();
        rmaService.createReturn(req(key, 100L, "불량", items(501, 1, 2)));
        assertThatThrownBy(() -> rmaService.createReturn(req(key, 100L, "변심", items(501, 1, 2))))
                .isInstanceOf(RmaService.DuplicateKeyConflictException.class);
    }

    @Test
    void 예약없는_주문이면_거부() {
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 999L, "불량", items(501, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("예약이 없습니다");
    }

    @Test
    void 미출고_주문이면_거부() {
        inventoryRepo.save(Inventory.create(1L, 10));
        inventoryService.reserveAll(100L, Map.of(1L, 5));
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출고되지 않은");
    }

    @Test
    void 출고내역에_없는_상품이면_거부() {
        seedAndShip(100L, Map.of(1L, 5));
        inventoryRepo.save(Inventory.create(99L, 10));
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 100L, "불량", items(501, 99, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출고 내역에 없는 상품");
    }

    @Test
    void 누적반품량이_출고량을_초과하면_거부() {
        seedAndShip(100L, Map.of(1L, 3));
        rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2)));
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 100L, "불량", items(502, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("누적 반품량이 출고량을 초과");
    }

    @Test
    void 수량0이하는_거부() {
        seedAndShip(100L, Map.of(1L, 5));
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수량은 1 이상");
    }

    // ── 동일 productId 여러 orderItemId ──────────────────────────

    @Test
    void 동일상품_여러_orderItemId는_productId별_합산으로_검증한다() {
        seedAndShip(100L, Map.of(1L, 5));
        var multiItems = List.of(
                new CreateRmaRequest.Item(501L, 1L, 2),
                new CreateRmaRequest.Item(502L, 1L, 3));
        var result = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", multiItems));
        assertThat(result.rma().getItems()).hasSize(2);
    }

    @Test
    void 동일상품_합산이_출고량_초과하면_거부() {
        seedAndShip(100L, Map.of(1L, 3));
        var multiItems = List.of(
                new CreateRmaRequest.Item(501L, 1L, 2),
                new CreateRmaRequest.Item(502L, 1L, 2));
        assertThatThrownBy(() -> rmaService.createReturn(
                req(UUID.randomUUID().toString(), 100L, "불량", multiItems)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("누적 반품량이 출고량을 초과");
    }

    // ── 입고·완료·취소 ────────────────────────────────────────────

    @Test
    void 입고처리_REQUESTED에서_RECEIVED로_전환() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        assertThat(rmaService.findById(rma.getId()).getStatus()).isEqualTo(RmaStatus.RECEIVED);
    }

    // 통지가 없으면 OMS는 60초 스윕으로만 RECEIVED를 발견한다 — 그 안에 검수가 끝나면
    // 고객 화면이 접수에서 완료로 건너뛴다. 입고도 통지 대상이라는 계약을 고정한다.
    @Test
    void 입고처리하면_OMS에_RECEIVED를_통지한다() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();

        rmaService.receive(rma.getId());

        var captor = org.mockito.ArgumentCaptor.forClass(RmaReturn.class);
        verify(returnNotifier).notifyAfterCommit(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RmaStatus.RECEIVED);
    }

    // OMS는 RECEIVED 품목에 acceptedQuantity=0, disposition=null을 요구한다(계약 불일치면 409).
    @Test
    void 입고_통지의_품목은_승인수량0_처분없음이다() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();

        rmaService.receive(rma.getId());

        var captor = org.mockito.ArgumentCaptor.forClass(RmaReturn.class);
        verify(returnNotifier).notifyAfterCommit(captor.capture());
        var payload = com.jhg.wms.web.RmaResponse.from(captor.getValue());
        assertThat(payload.status()).isEqualTo("RECEIVED");
        assertThat(payload.items()).singleElement().satisfies(item -> {
            assertThat(item.acceptedQuantity()).isZero();
            assertThat(item.disposition()).isNull();
        });
    }

    @Test
    void 입고_전이가_거부되면_통지하지_않는다() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        reset(returnNotifier);

        assertThatThrownBy(() -> rmaService.receive(rma.getId()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(returnNotifier);
    }

    @Test
    void 검수완료_RESTOCKED이면_재고가_증가한다() {
        seedAndShip(100L, Map.of(1L, 5));
        int beforeOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();

        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(1, RmaDisposition.RESTOCKED)));

        assertThat(rmaService.findById(rma.getId()).getStatus()).isEqualTo(RmaStatus.COMPLETED);
        int afterOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();
        assertThat(afterOnHand).isEqualTo(beforeOnHand + 1);
    }

    @Test
    void 검수완료_DISPOSED면_재고_변경_없음() {
        seedAndShip(100L, Map.of(1L, 5));
        int beforeOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();

        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(2, RmaDisposition.DISPOSED)));

        int afterOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();
        assertThat(afterOnHand).isEqualTo(beforeOnHand);
    }

    @Test
    void 검수완료_REJECTED면_재고_변경_없음() {
        seedAndShip(100L, Map.of(1L, 5));
        int beforeOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();

        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(0, RmaDisposition.REJECTED)));

        int afterOnHand = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();
        assertThat(afterOnHand).isEqualTo(beforeOnHand);
    }

    @Test
    void 검수완료_RESTOCKED이면_RETURN_트랜잭션이_남는다() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(1, RmaDisposition.RESTOCKED)));

        var returns = txnRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.RETURN).toList();
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0).getDelta()).isEqualTo(1);
        assertThat(returns.get(0).getReference()).contains("RMA#");
    }

    @Test
    void 취소는_REQUESTED에서만_가능() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        assertThatThrownBy(() -> rmaService.cancel(rma.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 취소하면_CANCELLED() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.cancel(rma.getId());
        assertThat(rmaService.findById(rma.getId()).getStatus()).isEqualTo(RmaStatus.CANCELLED);
    }

    @Test
    void 취소된_RMA는_누적반품량에서_제외된다() {
        seedAndShip(100L, Map.of(1L, 3));
        var rma1 = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 3))).rma();
        rmaService.cancel(rma1.getId());
        // 취소 후 다시 3개 반품 가능
        var rma2 = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(502, 1, 3)));
        assertThat(rma2.created()).isTrue();
    }

    @Test
    void 완료된_RMA는_승인수량만_누적에_반영된다() {
        seedAndShip(100L, Map.of(1L, 5));
        // 3개 요청, 1개만 승인
        var rma1 = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 3))).rma();
        rmaService.receive(rma1.getId());
        rmaService.complete(rma1.getId(), Map.of(rma1.getItems().get(0).getId(),
                new RmaService.InspectionResult(1, RmaDisposition.RESTOCKED)));

        // 누적 승인 1 + 새 요청 4 = 5 ≤ 출고 5 → 성공
        var rma2 = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(502, 1, 4)));
        assertThat(rma2.created()).isTrue();
    }

    // ── 검수 검증 ─────────────────────────────────────────────────

    @Test
    void 승인수량이_요청수량_초과하면_거부() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        assertThatThrownBy(() -> rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(3, RmaDisposition.RESTOCKED))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인0에_RESTOCKED이면_거부() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        assertThatThrownBy(() -> rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(0, RmaDisposition.RESTOCKED))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인있는데_REJECTED이면_거부() {
        seedAndShip(100L, Map.of(1L, 5));
        var rma = rmaService.createReturn(req(UUID.randomUUID().toString(), 100L, "불량", items(501, 1, 2))).rma();
        rmaService.receive(rma.getId());
        Long itemId = rma.getItems().get(0).getId();
        assertThatThrownBy(() -> rmaService.complete(rma.getId(), Map.of(itemId,
                new RmaService.InspectionResult(1, RmaDisposition.REJECTED))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
