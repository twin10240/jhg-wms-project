package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.service.RmaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 관리자 화면은 폼 로그인 + 롤 인가(SecurityConfig webChain) — 조회/입고는 OPERATOR, 발주 생성·보충요청 승인/거절은 MANAGER.
// webChain은 DbUserDetailsService에 의존하므로 슬라이스 컨텍스트 로딩을 위해 목빈이 필요(직접 호출되지는 않음 — .with(user(...))로 principal 주입).
@WebMvcTest(WmsAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class WmsAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean PurchaseOrderService purchaseOrderService;
    @MockitoBean ReplenishmentRequestService replenishmentRequestService;
    @MockitoBean RmaService rmaService;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 재고화면_보유_예약_가용_컬럼을_렌더링한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/inventory").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(content().string(containsString("가용")))
                .andExpect(content().string(containsString("상품 1")))
                .andExpect(content().string(containsString("admin.css")));
    }

    @Test
    void 이력화면에_transactions_모델이_담긴다() throws Exception {
        when(inventoryService.findTransactions(eq(null), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory-transactions"))
                .andExpect(model().attributeExists("transactions"));
    }

    @Test
    void 이력화면_트랜잭션_유형_필터가_동작한다() throws Exception {
        InventoryTransaction receive = InventoryTransaction.of(1L, InventoryTransactionType.RECEIVE, 10, 0, 10, "PO#1", null, null);
        when(inventoryService.findTransactions(eq(InventoryTransactionType.RECEIVE), any()))
                .thenReturn(new PageImpl<>(List.of(receive)));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")).param("type", "RECEIVE"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("transactions", List.of(receive)))
                .andExpect(model().attribute("filterType", InventoryTransactionType.RECEIVE));
    }

    @Test
    void 이력화면은_상품명_한글유형_한글참조를_표시한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 0, 10)));
        when(inventoryService.findTransactions(eq(null), any())).thenReturn(new PageImpl<>(List.of(
                InventoryTransaction.of(1L, InventoryTransactionType.SHIP, -2, 12, 10, "ORDER#52", null, null),
                InventoryTransaction.of(1L, InventoryTransactionType.RECEIVE, 5, 10, 15, "PO#7", null, null))));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("productNames"))
                .andExpect(content().string(containsString("출고")))      // SHIP 한글
                .andExpect(content().string(containsString("주문 #52")))  // ORDER#52 한글
                .andExpect(content().string(containsString("입고")))      // RECEIVE 한글
                .andExpect(content().string(containsString("발주 #7")))   // PO#7 한글
                .andExpect(content().string(containsString("12 → 10")))   // SHIP 변경 전 → 후
                .andExpect(content().string(containsString("10 → 15")));  // RECEIVE 변경 전 → 후
    }

    @Test
    void 재고화면은_이력_페이지_링크를_보여준다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 0, 10)));

        mockMvc.perform(get("/admin/inventory").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/inventory/transactions")));
    }

    @Test
    void 대시보드_재고_발주_예약_요약을_모델에_담는다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(
                new InventoryRowResponse(1L, "상품 1", 10, 3, 7),
                new InventoryRowResponse(2L, "상품 2", 5, 0, 5),
                new InventoryRowResponse(3L, "상품 3", 0, 0, 0)));   // 가용 0
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(
                PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10))));
        when(replenishmentRequestService.findAll()).thenReturn(List.of(
                ReplenishmentRequest.create(UUID.randomUUID(), "부족", ReplenishmentRequestItem.create(1L, 5))));
        Reservation shipped = Reservation.reserve(2L, Map.of(1L, 1));
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(1L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attribute("skuCount", 3))
                .andExpect(model().attribute("totalOnHand", 15))
                .andExpect(model().attribute("totalReserved", 3))
                .andExpect(model().attribute("totalAvailable", 12))
                .andExpect(model().attribute("orderedPoCount", 1L))
                .andExpect(model().attribute("reservedCount", 1L))
                .andExpect(model().attribute("shippedCount", 1L))
                .andExpect(model().attribute("releasedCount", 0L))
                // 처리 대기 카드
                .andExpect(model().attribute("pendingRequestCount", 1L))   // 검토 대기 보충 요청
                .andExpect(model().attribute("partialPoCount", 0L))        // 부분 입고 발주
                .andExpect(model().attribute("zeroAvailableCount", 1L));   // 가용 0 SKU
    }

    @Test
    void 예약화면_전체_목록을_렌더링한다() throws Exception {
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L, Map.of(1L, 1))));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
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

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")).param("status", "SHIPPED"))
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

        mockMvc.perform(get("/admin/purchase-orders").with(user("op").roles("OPERATOR")).param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("purchaseOrders", List.of(received)));
    }

    @Test
    void 발주_상세_페이지를_렌더링한다() throws Exception {
        PurchaseOrderItem item = PurchaseOrderItem.create(1L, 10);
        ReflectionTestUtils.setField(item, "id", 42L);
        PurchaseOrder po = PurchaseOrder.create("발주", item);
        when(purchaseOrderService.findWithItems(1L)).thenReturn(po);

        mockMvc.perform(get("/admin/purchase-orders/1").with(user("op").roles("OPERATOR")))
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

        mockMvc.perform(get("/admin/purchase-orders/1").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("items[0].itemId"),
                        containsString("items[1].itemId"),
                        containsString("items[2].itemId"))));
    }

    @Test
    void 입고_폼_제출은_품목별_수량을_서비스에_전달한다() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(user("op").roles("OPERATOR")).with(csrf())
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

        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(user("op").roles("OPERATOR")).with(csrf())
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

        mockMvc.perform(get("/admin/replenishment-requests").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/replenishmentrequests"))
                .andExpect(model().attribute("requests", List.of(request)));
    }

    @Test
    void approvesReplenishmentRequest() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests/7/approve")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("wmsMemo", "ready"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(replenishmentRequestService).approve(7L, "ready");
    }

    @Test
    void rejectRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests/7/reject")
                        .with(user("mgr").roles("MANAGER"))
                        .param("wmsMemo", "no"))
                .andExpect(status().isForbidden());
    }

    @Test
    void decisionStateErrorRedirectsWithFlash() throws Exception {
        doThrow(new IllegalStateException("already decided"))
                .when(replenishmentRequestService).reject(7L, "late");

        mockMvc.perform(post("/admin/replenishment-requests/7/reject")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("wmsMemo", "late"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attribute("errorMessage", "already decided"));
    }

    @Test
    void OPERATOR는_발주_생성이_403() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders").with(user("op").roles("OPERATOR")).with(csrf())
                        .param("items[0].productId", "1").param("items[0].quantity", "5"))
                .andExpect(status().isForbidden());
    }

    @Test
    void OPERATOR는_보충요청_승인이_403() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests/7/approve").with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 취소된_발주_상세는_입고_입력칸을_보여주지_않는다() throws Exception {
        PurchaseOrderItem item = PurchaseOrderItem.create(1L, 10);
        ReflectionTestUtils.setField(item, "id", 42L);
        PurchaseOrder po = PurchaseOrder.create("취소 대상", item);
        po.cancel();
        when(purchaseOrderService.findWithItems(1L)).thenReturn(po);

        mockMvc.perform(get("/admin/purchase-orders/1").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("취소됨")))
                .andExpect(content().string(not(containsString("type=\"number\""))));   // 입고 입력칸 없음
    }

    @Test
    void MANAGER는_발주를_취소할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders/1/cancel")
                        .with(user("mgr").roles("MANAGER")).with(csrf()))
               .andExpect(status().is3xxRedirection());
        verify(purchaseOrderService).cancel(1L);
    }

    @Test
    void OPERATOR는_발주_취소가_403() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders/1/cancel")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
               .andExpect(status().isForbidden());
        verifyNoInteractions(purchaseOrderService);
    }

    // 수불대장 합계행은 데이터가 있을 때만 렌더링된다 — 빈 목록만 검증하면 합계 표현식이 평가되지 않아 깨진 걸 못 잡는다.
    @Test
    void 수불대장은_합계행에_컬럼별_총계를_렌더링한다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, -2, 106),
                new InventoryService.LedgerRow(2L, "상품 2", 50, 0, 10, 0, -5, 0, 55)));

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory-ledger"))
                .andExpect(content().string(allOf(
                        containsString("합계"),
                        containsString(">150<"),    // 기초 100+50
                        containsString(">30<"),     // 입고 20+10
                        containsString(">-20<"),    // 출고 -15+-5
                        containsString(">161<"))));  // 기말 106+55
    }

    @Test
    void 수불대장_기간이_뒤집히면_500이_아니라_에러메시지를_렌더링한다() throws Exception {
        when(inventoryService.buildLedger(any(), any()))
                .thenThrow(new IllegalArgumentException("시작일이 종료일보다 뒤입니다."));

        mockMvc.perform(get("/admin/inventory/ledger")
                        .param("from", "2026-08-10").param("to", "2026-08-01")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory-ledger"))
                .andExpect(content().string(containsString("시작일이 종료일보다 뒤입니다.")));
    }

    // 대시보드로 되돌리면 대시보드가 다시 같은 예외를 던진다 — 리다이렉트 루프가 되지 않는지 고정한다.
    @Test
    void 대시보드_DB오류는_리다이렉트하지_않고_503을_그린다() throws Exception {
        when(inventoryService.findAllRows())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/").with(user("op").roles("OPERATOR")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"));
    }

    // 목록도 자기 자신이 DB를 타므로 같은 함정에 빠진다.
    @Test
    void 재고목록_DB오류도_리다이렉트하지_않는다() throws Exception {
        when(inventoryService.findAllRows())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/admin/inventory").with(user("op").roles("OPERATOR")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"));
    }
}
