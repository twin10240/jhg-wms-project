package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.domain.ReplenishmentRequestItem;
import com.jhg.wms.domain.ReplenishmentRequestStatus;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
class PurchaseOrderServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository adjustmentRepo;
    @Autowired PurchaseOrderRepository poRepo;
    @Autowired ReplenishmentRequestRepository requestRepo;
    InventoryService inventoryService;
    PurchaseOrderService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, adjustmentRepo, notifier,
                mock(com.jhg.wms.client.OmsDeliveryNotifier.class), () -> "system");
        service = new PurchaseOrderService(poRepo, inventoryService, requestRepo);
    }

    @Test
    void create_발주는_ORDERED_상태로_저장된다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 20)), "긴급 발주");
        PurchaseOrder saved = poRepo.findById(poId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(saved.getMemo()).isEqualTo("긴급 발주");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(20);
    }

    @Test
    void create_품목이_없으면_예외를_던진다() {
        assertThatThrownBy(() -> service.create(List.of(), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_수량이_0이면_예외를_던진다() {
        assertThatThrownBy(() -> service.create(List.of(new PurchaseOrderLine(1L, 0)), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 저장된 발주의 n번째 품목 id를 꺼낸다. */
    private Long itemIdOf(Long poId, int index) {
        return poRepo.findById(poId).orElseThrow().getItems().get(index).getId();
    }

    /** 발주일시를 명시적으로 지정한다(생성 시각이 마이크로초 차이라 테스트가 흔들리는 것을 막는다). */
    private void setCreatedAt(Long poId, LocalDateTime at) {
        ReflectionTestUtils.setField(poRepo.findById(poId).orElseThrow(), "createdAt", at);
    }

    @Test
    void findAllWithItems_오래된_발주부터_보여주되_종료된_건은_뒤로_보낸다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 100));
        Long oldestCancelled = service.create(List.of(new PurchaseOrderLine(1L, 5)), "가장 오래됨 - 취소됨");
        Long oldestDone = service.create(List.of(new PurchaseOrderLine(1L, 5)), "오래됨 - 입고완료");
        Long older = service.create(List.of(new PurchaseOrderLine(1L, 5)), "오래됨 - 대기");
        Long newer = service.create(List.of(new PurchaseOrderLine(1L, 5)), "최신 - 대기");

        service.cancel(oldestCancelled);                                            // → CANCELLED
        service.receive(oldestDone, java.util.Map.of(itemIdOf(oldestDone, 0), 5));  // 전량 입고 → RECEIVED
        setCreatedAt(oldestCancelled, LocalDateTime.of(2026, 1, 1, 8, 0));
        setCreatedAt(oldestDone, LocalDateTime.of(2026, 1, 1, 9, 0));
        setCreatedAt(older, LocalDateTime.of(2026, 1, 2, 9, 0));
        setCreatedAt(newer, LocalDateTime.of(2026, 1, 3, 9, 0));
        poRepo.flush();

        List<PurchaseOrder> result = service.findAllWithItems();

        // 미완료가 오래된 순으로 먼저, 종료된 건(입고완료·취소됨)은 가장 오래됐어도 맨 뒤
        assertThat(result).extracting(PurchaseOrder::getId)
                .containsExactly(older, newer, oldestCancelled, oldestDone);
    }

    @Test
    void receive_전량_입고하면_RECEIVED가_되고_재고가_늘어난다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 7)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 7));

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getItems().get(0).getReceivedQty()).isEqualTo(7);
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(17);
    }

    @Test
    void receive_부분_입고하면_들어온_수량만_재고와_원장에_반영된다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 100)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 60));

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(70);
        var receives = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.RECEIVE)
                .toList();
        assertThat(receives).hasSize(1);
        assertThat(receives.get(0).getDelta()).isEqualTo(60);
        assertThat(receives.get(0).getReference()).isEqualTo("PO#" + poId);
    }

    @Test
    void receive_qty0인_품목은_원장에_행을_남기지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(2L, "상품 2", 10));
        Long poId = service.create(
                List.of(new PurchaseOrderLine(1L, 5), new PurchaseOrderLine(2L, 5)), "발주");
        var input = new java.util.LinkedHashMap<Long, Integer>();
        input.put(itemIdOf(poId, 0), 5);
        input.put(itemIdOf(poId, 1), 0);

        service.receive(poId, input);

        var receives = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.RECEIVE)
                .toList();
        assertThat(receives).hasSize(1);
        assertThat(receives.get(0).getProductId()).isEqualTo(1L);
        assertThat(inventoryRepo.findByProductId(2L).orElseThrow().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void receive_잔량_초과면_재고도_원장도_변하지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");

        assertThatThrownBy(() -> service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 9)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(10);
        assertThat(adjustmentRepo.findAllByOrderByIdDesc()).isEmpty();
    }

    @Test
    void receive_없는_발주는_예외를_던진다() {
        assertThatThrownBy(() -> service.receive(999L, java.util.Map.of(1L, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_입고완료된_발주에_또_입고하면_예외를_던진다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");
        Long itemId = itemIdOf(poId, 0);
        service.receive(poId, java.util.Map.of(itemId, 5));

        assertThatThrownBy(() -> service.receive(poId, java.util.Map.of(itemId, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receive_입고하면_상품별로_OMS_통지를_예약한다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 5));

        verify(notifier).notifyAfterCommit(1L);
    }

    @Test
    void receive_부분_입고에서는_보충요청을_이행하지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");
        ReplenishmentRequest request = ReplenishmentRequest.create(UUID.randomUUID(), "저재고",
                ReplenishmentRequestItem.create(1L, 10));
        request.approve(poId, "승인");
        requestRepo.save(request);

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 4));

        assertThat(requestRepo.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ReplenishmentRequestStatus.APPROVED);
    }

    @Test
    void receive_전량_입고하면_보충요청이_이행된다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");
        ReplenishmentRequest request = ReplenishmentRequest.create(UUID.randomUUID(), "저재고",
                ReplenishmentRequestItem.create(1L, 10));
        request.approve(poId, "승인");
        requestRepo.save(request);

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 10));

        assertThat(requestRepo.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ReplenishmentRequestStatus.FULFILLED);
    }

    @Test
    void findWithItems_품목까지_함께_조회한다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 3)), "발주");

        PurchaseOrder po = service.findWithItems(poId);

        assertThat(po.getId()).isEqualTo(poId);
        assertThat(po.getItems()).hasSize(1);
    }

    @Test
    void cancel_부분입고_발주를_취소하고_연결요청을_CANCELLED로_한다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 100)), "발주");

        // 연결 요청 생성 + 승인으로 poId 연결(approve는 REQUESTED에서만 → 신규 요청 사용)
        ReplenishmentRequest req = ReplenishmentRequest.create(UUID.randomUUID(), "부족",
                ReplenishmentRequestItem.create(1L, 100));
        req.approve(poId, "발주함");
        requestRepo.save(req);

        // 부분 입고 → PARTIALLY_RECEIVED
        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 60));
        int onHandBefore = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();

        service.cancel(poId);

        assertThat(poRepo.findById(poId).orElseThrow().getStatus())
                .isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(requestRepo.findByPurchaseOrderId(poId).orElseThrow().getStatus())
                .isEqualTo(ReplenishmentRequestStatus.CANCELLED);
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty())
                .isEqualTo(onHandBefore);   // 재고 불변
    }

    @Test
    void cancel_연결요청이_없어도_발주만_취소된다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "직접 발주");
        service.cancel(poId);
        assertThat(poRepo.findById(poId).orElseThrow().getStatus())
                .isEqualTo(PurchaseOrderStatus.CANCELLED);
    }
}
