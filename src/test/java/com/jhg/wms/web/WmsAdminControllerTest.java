package com.jhg.wms.web;

import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.ReplenishmentRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 관리자 화면은 인증 필요(SecurityConfig) — 모든 GET 호출에 httpBasic("wms","wms") 부여.
@WebMvcTest(WmsAdminController.class)
@Import(SecurityConfig.class)
class WmsAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean PurchaseOrderService purchaseOrderService;
    @MockitoBean ReplenishmentRequestService replenishmentRequestService;

    @Test
    void 재고화면_보유_예약_가용_컬럼을_렌더링한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/inventory").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(content().string(containsString("가용")))
                .andExpect(content().string(containsString("상품 1")))
                .andExpect(content().string(containsString("admin.css")));
    }

    @Test
    void inventory_화면에_transactions_모델이_담긴다() throws Exception {
        when(inventoryService.findTransactions(null)).thenReturn(List.of());

        mockMvc.perform(get("/admin/inventory").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("transactions"));
    }

    @Test
    void 재고화면_트랜잭션_유형_필터가_동작한다() throws Exception {
        InventoryTransaction receive = InventoryTransaction.of(1L, InventoryTransactionType.RECEIVE, 10, 0, 10, "PO#1", null);
        when(inventoryService.findTransactions(InventoryTransactionType.RECEIVE)).thenReturn(List.of(receive));

        mockMvc.perform(get("/admin/inventory").with(httpBasic("wms", "wms")).param("type", "RECEIVE"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("transactions", List.of(receive)))
                .andExpect(model().attribute("filterType", InventoryTransactionType.RECEIVE));
    }

    @Test
    void 대시보드_재고_발주_예약_요약을_모델에_담는다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(
                new InventoryRowResponse(1L, "상품 1", 10, 3, 7),
                new InventoryRowResponse(2L, "상품 2", 5, 0, 5)));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(
                PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10))));
        Reservation shipped = Reservation.reserve(2L, Map.of(1L, 1));
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(1L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/").with(httpBasic("wms", "wms")))
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
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L, Map.of(1L, 1))));

        mockMvc.perform(get("/admin/reservations").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reservations"))
                .andExpect(content().string(containsString("10")))
                .andExpect(content().string(containsString("RESERVED")));
    }

    @Test
    void 예약화면_상태_필터가_동작한다() throws Exception {
        Reservation shipped = Reservation.reserve(20L, Map.of(1L, 1));
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/admin/reservations").with(httpBasic("wms", "wms")).param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("reservations", List.of(shipped)));
    }

    @Test
    void 발주화면_상태_필터가_동작한다() throws Exception {
        PurchaseOrder ordered = PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10));
        PurchaseOrderItem receivedItem = PurchaseOrderItem.create(2L, 5);
        ReflectionTestUtils.setField(receivedItem, "id", 1L);
        PurchaseOrder received = PurchaseOrder.create("완료", receivedItem);
        received.receive(Map.of(1L, 5));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(ordered, received));
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 0, 10)));

        mockMvc.perform(get("/admin/purchase-orders").with(httpBasic("wms", "wms")).param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("purchaseOrders", List.of(received)));
    }

    @Test
    void 발주_상세_페이지를_렌더링한다() throws Exception {
        PurchaseOrderItem item = PurchaseOrderItem.create(1L, 10);
        ReflectionTestUtils.setField(item, "id", 42L);
        PurchaseOrder po = PurchaseOrder.create("발주", item);
        when(purchaseOrderService.findWithItems(1L)).thenReturn(po);

        mockMvc.perform(get("/admin/purchase-orders/1").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/purchaseorderdetail"))
                .andExpect(model().attribute("po", po));
    }

    @Test
    void 발주_상세_페이지는_완료된_품목도_itemId를_방출한다_인덱스_갭_방지() throws Exception {
        PurchaseOrderItem first = PurchaseOrderItem.create(1L, 10);
        ReflectionTestUtils.setField(first, "id", 42L);
        PurchaseOrderItem middle = PurchaseOrderItem.create(2L, 5);
        ReflectionTestUtils.setField(middle, "id", 43L);
        PurchaseOrderItem last = PurchaseOrderItem.create(3L, 7);
        ReflectionTestUtils.setField(last, "id", 44L);
        PurchaseOrder po = PurchaseOrder.create("발주", first, middle, last);
        po.receive(Map.of(43L, 5)); // 가운데 품목만 완료 처리 — 이후 품목의 인덱스가 앞당겨지지 않아야 한다.
        when(purchaseOrderService.findWithItems(1L)).thenReturn(po);

        mockMvc.perform(get("/admin/purchase-orders/1").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("items[0].itemId"),
                        containsString("items[1].itemId"),
                        containsString("items[2].itemId"))));
    }

    @Test
    void 입고_폼_제출은_품목별_수량을_서비스에_전달한다() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(httpBasic("wms", "wms")).with(csrf())
                        .param("items[0].itemId", "42")
                        .param("items[0].quantity", "6")
                        .param("items[1].itemId", "43")
                        .param("items[1].quantity", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders/1"));

        var expected = new java.util.LinkedHashMap<Long, Integer>();
        expected.put(42L, 6);
        expected.put(43L, 0);
        verify(purchaseOrderService).receive(1L, expected);
    }

    @Test
    void 입고_실패하면_에러_플래시를_담는다() throws Exception {
        doThrow(new IllegalArgumentException("잔량 40개를 초과했습니다"))
                .when(purchaseOrderService).receive(eq(1L), anyMap());

        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(httpBasic("wms", "wms")).with(csrf())
                        .param("items[0].itemId", "42")
                        .param("items[0].quantity", "99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "잔량 40개를 초과했습니다"));
    }

    @Test
    void replenishmentRequestsShowsHistory() throws Exception {
        ReplenishmentRequest request = ReplenishmentRequest.create(UUID.randomUUID(), "low stock",
                ReplenishmentRequestItem.create(1L, 3));
        ReflectionTestUtils.setField(request, "id", 7L);
        when(replenishmentRequestService.findAll()).thenReturn(List.of(request));

        mockMvc.perform(get("/admin/replenishment-requests").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/replenishmentrequests"))
                .andExpect(model().attribute("requests", List.of(request)));
    }

    @Test
    void approvesReplenishmentRequest() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests/7/approve")
                        .with(httpBasic("wms", "wms")).with(csrf())
                        .param("wmsMemo", "ready"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(replenishmentRequestService).approve(7L, "ready");
    }

    @Test
    void rejectRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests/7/reject")
                        .with(httpBasic("wms", "wms"))
                        .param("wmsMemo", "no"))
                .andExpect(status().isForbidden());
    }

    @Test
    void decisionStateErrorRedirectsWithFlash() throws Exception {
        doThrow(new IllegalStateException("already decided"))
                .when(replenishmentRequestService).reject(7L, "late");

        mockMvc.perform(post("/admin/replenishment-requests/7/reject")
                        .with(httpBasic("wms", "wms")).with(csrf())
                        .param("wmsMemo", "late"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attribute("errorMessage", "already decided"));
    }
}
