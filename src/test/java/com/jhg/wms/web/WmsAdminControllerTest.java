package com.jhg.wms.web;

import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WmsAdminController.class)
class WmsAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean PurchaseOrderService purchaseOrderService;

    @Test
    void 재고화면_보유_예약_가용_컬럼을_렌더링한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10, 3, 7)));

        mockMvc.perform(get("/admin/inventory"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(content().string(containsString("가용")))
                .andExpect(content().string(containsString("admin.css")));
    }

    @Test
    void 대시보드_재고_발주_예약_요약을_모델에_담는다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(
                new InventoryRowResponse(1L, 10, 3, 7),
                new InventoryRowResponse(2L, 5, 0, 5)));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(
                PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10))));
        Reservation shipped = Reservation.reserve(2L);
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(1L), shipped));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attribute("skuCount", 2))
                .andExpect(model().attribute("totalOnHand", 15))
                .andExpect(model().attribute("totalReserved", 3))
                .andExpect(model().attribute("totalAvailable", 12))
                .andExpect(model().attribute("orderedPoCount", 1L))
                .andExpect(model().attribute("reservedCount", 1L))
                .andExpect(model().attribute("shippedCount", 1L))
                .andExpect(model().attribute("releasedCount", 0L));
    }

    @Test
    void 예약화면_전체_목록을_렌더링한다() throws Exception {
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L)));

        mockMvc.perform(get("/admin/reservations"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reservations"))
                .andExpect(content().string(containsString("10")))
                .andExpect(content().string(containsString("RESERVED")));
    }

    @Test
    void 예약화면_상태_필터가_동작한다() throws Exception {
        Reservation shipped = Reservation.reserve(20L);
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L), shipped));

        mockMvc.perform(get("/admin/reservations").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("reservations", List.of(shipped)));
    }

    @Test
    void 발주화면_상태_필터가_동작한다() throws Exception {
        PurchaseOrder ordered = PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10));
        PurchaseOrder received = PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 5));
        received.receive();
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(ordered, received));
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10, 0, 10)));

        mockMvc.perform(get("/admin/purchase-orders").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("purchaseOrders", List.of(received)));
    }
}
