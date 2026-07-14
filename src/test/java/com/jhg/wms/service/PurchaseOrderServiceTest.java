package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.repository.InventoryAdjustmentRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
class PurchaseOrderServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryAdjustmentRepository adjustmentRepo;
    @Autowired PurchaseOrderRepository poRepo;
    InventoryService inventoryService;
    PurchaseOrderService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, adjustmentRepo, notifier);
        service = new PurchaseOrderService(poRepo, inventoryService);
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

    @Test
    void receive_입고하면_RECEIVED가_되고_재고가_늘어난다() {
        // 재고 시드
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 5));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");

        service.receive(poId);

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
    }

    @Test
    void receive_없는_발주는_예외를_던진다() {
        assertThatThrownBy(() -> service.receive(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void receive_중복_입고는_예외를_던진다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 0));
        service.receive(poId);
        assertThatThrownBy(() -> service.receive(poId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receive_입고하면_품목별로_OMS_통지를_예약한다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 5));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");

        service.receive(poId);

        verify(notifier).notifyAfterCommit(1L);
    }
}
