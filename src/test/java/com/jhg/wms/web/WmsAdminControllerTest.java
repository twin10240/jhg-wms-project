package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.service.RmaService;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.ReturnAnalyticsService;
import java.time.LocalDate;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.jhg.wms.support.OrderKeys.keyOf;

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
    @MockitoBean CycleCountService cycleCountService;
    @MockitoBean ReturnAnalyticsService returnAnalyticsService;
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
        when(inventoryService.findTransactions(eq(null), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory-transactions"))
                .andExpect(model().attributeExists("transactions"));
    }

    @Test
    void 이력화면_트랜잭션_유형_필터가_동작한다() throws Exception {
        InventoryTransaction receive = InventoryTransaction.of(1L, InventoryTransactionType.RECEIVE, 10, 0, 10, "PO#1", null, null);
        when(inventoryService.findTransactions(eq(InventoryTransactionType.RECEIVE), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(receive)));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")).param("type", "RECEIVE"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("transactions", List.of(receive)))
                .andExpect(model().attribute("filterType", InventoryTransactionType.RECEIVE));
    }

    @Test
    void 이력화면은_상품명_한글유형_한글참조를_표시한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 0, 10)));
        when(inventoryService.findTransactions(eq(null), eq(null), eq(null), eq(null), any())).thenReturn(new PageImpl<>(List.of(
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
        Reservation shipped = Reservation.reserve(keyOf(2L), 2L, Map.of(1L, 1));
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(keyOf(1L), 1L, Map.of(1L, 1)), shipped));

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
                .andExpect(model().attribute("deliveryPendingCount", 1L))   // 출고됐고 배송 완료 미기록
                // 처리 대기 카드
                .andExpect(model().attribute("pendingRequestCount", 1L))   // 검토 대기 보충 요청
                .andExpect(model().attribute("partialPoCount", 0L))        // 부분 입고 발주
                .andExpect(model().attribute("zeroAvailableCount", 1L));   // 가용 0 SKU
    }

    @Test
    void 예약화면_전체_목록을_렌더링한다() throws Exception {
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(keyOf(10L), 10L, Map.of(1L, 1))));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reservations"))
                .andExpect(content().string(containsString("10")))
                .andExpect(content().string(containsString("RESERVED")));
    }

    @Test
    void 예약화면_상태_필터가_동작한다() throws Exception {
        Reservation shipped = Reservation.reserve(keyOf(20L), 20L, Map.of(1L, 1));
        shipped.ship();
        when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(keyOf(10L), 10L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")).param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("reservations", List.of(shipped)));
    }

    @Test
    void 예약화면_배송대기_탭은_출고완료중_미배송만_남긴다() throws Exception {
        Reservation reserved = Reservation.reserve(keyOf(10L), 10L, Map.of(1L, 1));
        Reservation pending = Reservation.reserve(keyOf(20L), 20L, Map.of(1L, 1));
        pending.ship();
        pending.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        Reservation done = Reservation.reserve(keyOf(30L), 30L, Map.of(1L, 1));
        done.ship();
        done.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        done.deliver(java.time.Instant.parse("2026-08-28T01:00:00Z"));
        when(inventoryService.findAllReservations()).thenReturn(List.of(reserved, pending, done));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR"))
                        .param("pendingDelivery", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("reservations", List.of(pending)));
    }

    @Test
    void 예약화면_송장번호를_보여준다() throws Exception {
        Reservation shipped = Reservation.reserve(keyOf(20L), 20L, Map.of(1L, 1));
        shipped.ship();
        shipped.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        when(inventoryService.findAllReservations()).thenReturn(List.of(shipped));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MOCK-20-20260827063000")));
    }

    @Test
    void 예약화면_배송완료_버튼은_출고완료_행에만_나온다() throws Exception {
        Reservation shipped = Reservation.reserve(keyOf(20L), 20L, Map.of(1L, 1));
        shipped.ship();
        shipped.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        when(inventoryService.findAllReservations())
                .thenReturn(List.of(Reservation.reserve(keyOf(10L), 10L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/reservations/00000000-0000-0000-0000-000000000014/deliver")))
                .andExpect(content().string(not(containsString("/admin/reservations/00000000-0000-0000-0000-00000000000a/deliver"))));
    }

    @Test
    void 예약화면_배송완료된_행은_시각과_재통지_버튼을_보여준다() throws Exception {
        Reservation delivered = Reservation.reserve(keyOf(30L), 30L, Map.of(1L, 1));
        delivered.ship();
        delivered.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        delivered.deliver(java.time.Instant.parse("2026-08-28T01:00:00Z"));
        when(inventoryService.findAllReservations()).thenReturn(List.of(delivered));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("배송 완료 2026-08-28")))
                // 재통지 버튼은 남는다 — 최초 콜백이 실패했을 때의 유일한 복구 경로다.
                .andExpect(content().string(containsString("OMS 재통지")))
                .andExpect(content().string(containsString("/admin/reservations/00000000-0000-0000-0000-00000000001e/deliver")));
    }

    @Test
    void 배송완료_처리는_서비스를_호출하고_예약화면으로_돌아간다() throws Exception {
        when(inventoryService.markDelivered(keyOf(20L))).thenReturn(true);

        mockMvc.perform(post("/admin/reservations/00000000-0000-0000-0000-000000000014/deliver")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reservations"))
                .andExpect(flash().attribute("successMessage", "배송 완료 처리했습니다."));

        verify(inventoryService).markDelivered(keyOf(20L));
    }

    @Test
    void 이미_배송완료된_주문의_재통지는_통지_문구로_안내한다() throws Exception {
        when(inventoryService.markDelivered(keyOf(20L))).thenReturn(false);

        mockMvc.perform(post("/admin/reservations/00000000-0000-0000-0000-000000000014/deliver")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "OMS에 배송 완료를 다시 통지했습니다."));
    }

    @Test
    void 배송완료_실패하면_에러_플래시를_담는다() throws Exception {
        doThrow(new IllegalStateException("송장이 없어 배송 완료할 수 없습니다. orderId=20"))
                .when(inventoryService).markDelivered(keyOf(20L));

        mockMvc.perform(post("/admin/reservations/00000000-0000-0000-0000-000000000014/deliver")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "송장이 없어 배송 완료할 수 없습니다. orderId=20"));
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
    void 실사_중인_상품은_조정이_거부되고_서비스가_호출되지_않는다() throws Exception {
        doThrow(new IllegalStateException("실사가 진행 중인 상품은 조정할 수 없습니다."))
                .when(cycleCountService).assertAdjustable(1L);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("productId", "1").param("delta", "-2").param("reason", "파손"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attribute("errorMessage",
                        containsString("실사가 진행 중인 상품")));

        verify(inventoryService, never()).adjust(anyLong(), anyInt(), anyString());
    }

    // 권한 축소 회귀 방지 — adjust는 MANAGER 전용이 아니다(SecurityConfig 주석 참고).
    // 파손·오차의 현장 즉시 보정이 운영 요구이고, 통제는 원장 actor 추적으로 대신한다.
    @Test
    void OPERATOR도_재고를_조정할_수_있다() throws Exception {
        when(inventoryService.adjust(1L, -2, "파손")).thenReturn(8);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("productId", "1").param("delta", "-2").param("reason", "파손"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(inventoryService).adjust(1L, -2, "파손");
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
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, -2, 7, 113),
                new InventoryService.LedgerRow(2L, "상품 2", 50, 0, 10, 0, -5, 0, 5, 60)));

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory-ledger"))
                .andExpect(content().string(allOf(
                        containsString("합계"),
                        containsString(">150<"),    // 기초 100+50
                        containsString(">30<"),     // 입고 20+10
                        containsString(">-20<"),    // 출고 -15+-5
                        containsString(">12<"),     // 실사 7+5
                        containsString(">173<"))));  // 기말 113+60
    }

    @Test
    void 수불대장_기간이_오늘까지면_불변식_일치를_표시한다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 10, 0, 5, 0, -3, 0, 0, 12)));
        when(inventoryService.findInvariantViolations(anyList())).thenReturn(List.of());

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("원장 합계와 실제 보유수량이 일치")));
    }

    @Test
    void 수불대장_불변식이_깨지면_상품과_차이를_보여준다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 10, 0, 5, 0, -3, 0, 0, 12)));
        when(inventoryService.findInvariantViolations(anyList())).thenReturn(List.of(
                new InventoryService.InvariantViolation(1L, "상품 1", 12, 15)));

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("원장 합계와 실제 보유수량이 다릅니다")))
                .andExpect(content().string(containsString("상품 1")))
                .andExpect(content().string(containsString("12")))
                .andExpect(content().string(containsString("15")));
    }

    @Test
    void 수불대장_상품행은_상품과_기간을_실은_링크를_들고_있다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));
        when(inventoryService.findInvariantViolations(any())).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/inventory/ledger")
                        .with(user("op").roles("OPERATOR"))
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
                "data-href=\"/admin/inventory/transactions?productId=1&amp;from=2026-09-01&amp;to=2026-09-30\"");
    }

    // 합계 행은 상품이 아니다 — 눌리면 productId 없는 링크로 떨어진다.
    @Test
    void 수불대장_합계행에는_링크가_붙지_않는다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));
        when(inventoryService.findInvariantViolations(any())).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/inventory/ledger")
                        .with(user("op").roles("OPERATOR"))
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher tfoot = java.util.regex.Pattern
                .compile("<tfoot.*?</tfoot>", java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(tfoot.find()).as("tfoot 합계 블록이 렌더돼야 한다").isTrue();
        assertThat(tfoot.group()).doesNotContain("data-href");
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

    // 원장에 행위자를 남겨도 화면에 없으면 감사에 쓸 수 없다. 값이 없는 과거 행은 "—"로 구분해 보인다.
    @Test
    void 이력화면은_행위자를_보여주고_없으면_대시로_표시한다() throws Exception {
        InventoryTransaction withActor = InventoryTransaction.of(
                1L, InventoryTransactionType.ADJUST, 3, 10, 13, null, "파손 정정", "manager");
        InventoryTransaction legacy = InventoryTransaction.of(
                1L, InventoryTransactionType.ADJUST, 1, 13, 14, null, "구 데이터", null);
        when(inventoryService.findTransactions(eq(null), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(withActor, legacy)));

        String html = mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("행위자")))
                .andReturn().getResponse().getContentAsString();

        // 상품/참조 컬럼도 "—"를 쓰므로 존재 여부만으로는 행위자 컬럼을 못 잡는다.
        // 사유 컬럼(각 행마다 고유한 텍스트) 바로 다음 셀이 행위자 컬럼이므로, 그 위치의 값을 직접 확인한다.
        assertTrue(Pattern.compile("파손 정정</td>\\s*<td>manager</td>").matcher(html).find(),
                "행위자가 있는 행은 사유 컬럼 다음 셀에 사용자명을 표시해야 한다");
        assertTrue(Pattern.compile("구 데이터</td>\\s*<td>—</td>").matcher(html).find(),
                "행위자가 없는 행은 사유 컬럼 다음 셀에 —를 표시해야 한다");
    }

    @Test
    void 이력화면이_상품과_기간을_받아_서비스에_넘긴다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("productId", 1L))
                .andExpect(model().attribute("from", java.time.LocalDate.of(2026, 9, 1)))
                .andExpect(model().attribute("to", java.time.LocalDate.of(2026, 9, 30)));

        // Mockito는 매처를 하나라도 쓰면 전 인자를 매처로 요구한다 — null·1L도 eq()로 감싼다.
        verify(inventoryService).findTransactions(eq(null), eq(1L),
                eq(java.time.LocalDate.of(2026, 9, 1)), eq(java.time.LocalDate.of(2026, 9, 30)), any());
    }

    // 탭이 범위를 떨어뜨리면 유형을 누를 때마다 전역 저널로 튕겨 드릴다운이 한 번 쓰고 끝난다.
    @Test
    void 유형탭과_범위해제_링크가_범위를_유지하거나_턴다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 유형 탭은 범위를 실어 나른다 — 네 값이 같은 href 안에 다 있어야 한다.
        // 페이지 어딘가에 흩어져 있는 것만으로는(다른 탭이 productId를 들고 있어도)
        // 이 탭 하나가 범위를 놓쳤는지 구분하지 못한다.
        assertThat(html).containsPattern(
                "href=\"[^\"]*type=RECEIVE[^\"]*productId=1[^\"]*from=2026-09-01[^\"]*to=2026-09-30[^\"]*\"");
        // 범위 해제는 상품·기간을 떼고 유형만 남긴다
        assertThat(html).containsPattern("href=\"[^\"]*/admin/inventory/transactions\"[^>]*>\\s*범위 해제");
    }

    @Test
    void 범위가_없으면_범위배지를_렌더하지_않는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("범위 해제");
    }

    // to만 걸려도 findTransactions는 null인 from 쪽만 넓은 경계로 채우고 to는 그대로 좁은 경계로
    // 쓴다 — 결과가 실제로 좁혀지는데 배지가 없으면 사용자는 걸린 필터를 보지도, 끄지도 못한다.
    @Test
    void 이력화면이_종료일만_걸려도_범위배지를_렌더한다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("범위 해제");
        assertThat(html).contains("2026-09-30 까지");
    }

    @Test
    void 이력화면이_시작일만_걸리면_null을_문자열로_찍지_않는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("from", "2026-09-01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("2026-09-01 이후");
        // SpEL의 +는 null 피연산자를 문자열 "null"로 이어붙인다 — from만 있을 때 화면에
        // '~ null'이 그대로 찍히는 회귀는 위 텍스트 확인만으로는 못 잡으므로 따로 확인한다.
        assertThat(html).doesNotContain("~ null");
    }

    @Test
    void 범위가_다_걸리면_대조줄을_렌더한다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(inventoryService.ledgerRowOf(eq(1L), any(), any())).thenReturn(java.util.Optional.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 탭 라벨("기초")과 이동 줄 위 HTML 주석("이동")은 대조 줄이 통째로 사라져도
        // 그대로 남는다 — id로 대조 줄만 뽑아 태그를 지우고 각 숫자를 라벨에 붙여 확인해야
        // 델타(closing - opening) 계산이 실제로 검증된다.
        Matcher recon = Pattern.compile("<p[^>]*id=\"ledger-recon\"[^>]*>(.*?)</p>", Pattern.DOTALL)
                .matcher(html);
        assertThat(recon.find()).as("대조 줄이 렌더돼야 한다").isTrue();
        String reconText = recon.group(1).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        assertThat(reconText).isEqualTo("기초 100 + 이동 8 = 기말 108");
    }

    // 대조 줄은 기간 전체 기준인데, 유형 탭을 누르면 아래 목록은 그 유형만 남는다 — 줄이 그대로면
    // "요약과 상세가 어긋난다"는 이 기능이 막으려는 바로 그 오해를 사용자가 보게 된다.
    @Test
    void 유형이_걸리면_대조줄에_기간_전체_기준_안내가_붙는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(inventoryService.ledgerRowOf(eq(1L), any(), any())).thenReturn(java.util.Optional.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("type", "RECEIVE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher recon = Pattern.compile("<p[^>]*id=\"ledger-recon\"[^>]*>(.*?)</p>", Pattern.DOTALL)
                .matcher(html);
        assertThat(recon.find()).as("대조 줄이 렌더돼야 한다").isTrue();
        String reconText = recon.group(1).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        assertThat(reconText).contains("기간 전체 기준(아래 목록은 유형·페이지로 잘려 있습니다)");
    }

    // search(...)의 카운트 쿼리가 도는지는 서비스 테스트가 확인한다 — 여기서는 그 결과(45건,
    // 3페이지)를 받았을 때 "다음 →" 링크가 유형·상품·기간을 놓치지 않는지를 본다. 페이지 어딘가에
    // 흩어져 있는 것만으로는(다른 탭·다른 페이지 링크가 같은 토큰을 들고 있어도) 이 링크 하나가
    // 값을 놓쳤는지 구분하지 못하므로, "다음" 앵커의 href 속성값 하나로 범위를 좁혀 확인한다.
    @Test
    void 다음_페이지_링크가_유형_상품_기간을_모두_싣는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 45));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("type", "RECEIVE").param("productId", "1")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher nextLink = Pattern.compile("<a[^>]*href=\"([^\"]*)\"[^>]*>\\s*다음").matcher(html);
        assertThat(nextLink.find()).as("다음 페이지 링크가 렌더돼야 한다").isTrue();
        String href = nextLink.group(1);
        assertThat(href).containsPattern("type=RECEIVE.*productId=1.*from=2026-09-01.*to=2026-09-30");
    }

    // 범위가 없으면 기초·기말이 정의되지 않는다 — 빈 껍데기를 두지 않는다.
    @Test
    void 범위가_없으면_대조줄을_렌더하지_않고_조회도_하지_않는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR")).param("productId", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("ledger-recon");
        verify(inventoryService, never()).ledgerRowOf(any(), any(), any());
    }

    // from이 to보다 뒤인 역전 범위 — buildLedger가 던지는 IllegalArgumentException이
    // 이 핸들러까지 올라오면 500이 된다. 이전에는(대조 줄 도입 전) 같은 URL이 빈 목록으로
    // 정상 렌더됐으므로 그 동작을 그대로 지켜야 한다.
    @Test
    void 시작일이_종료일보다_뒤면_대조줄_없이_정상_렌더한다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("ledger-recon");
        verify(inventoryService, never()).ledgerRowOf(any(), any(), any());
    }

    @Test
    void 대시보드는_승인대기_실사_건수를_보여준다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of());
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of());
        when(replenishmentRequestService.findAll()).thenReturn(List.of());
        when(inventoryService.findAllReservations()).thenReturn(List.of());
        when(rmaService.findAll(null)).thenReturn(List.of());
        when(cycleCountService.countPendingApproval()).thenReturn(2L);

        mockMvc.perform(get("/").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingCycleCountCount", 2L))
                .andExpect(content().string(containsString("실사 승인 대기")));
    }

    @Test
    void 반품리포트_화면이_반품률과_범주_분포를_렌더링한다() throws Exception {
        var report = new ReturnAnalyticsService.ReturnRateReport(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 5,
                List.of(new ReturnAnalyticsService.ProductReturnRate(1L, "상품 1", 100, 8, 0.08)), 2);
        var breakdown = new ReturnAnalyticsService.CategoryBreakdown(
                List.of(new ReturnAnalyticsService.CategoryCount(
                        ReturnCategory.WRONG_ITEM, ReturnOwnerArea.PICKING, 3)), 1, 4);
        when(returnAnalyticsService.productReturnRates(any(), any())).thenReturn(report);
        when(returnAnalyticsService.categoryBreakdown(any(), any())).thenReturn(breakdown);

        mockMvc.perform(get("/admin/returns/report").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/return-report"))
                .andExpect(content().string(allOf(
                        containsString("8.0%"),           // 반품률이 퍼센트로 렌더링된다
                        containsString("피킹·출고"),        // 소관 라벨이 붙는다
                        containsString("<strong>전체</strong>"),          // 합계 행이 표 안에 있다
                        containsString("<strong>4</strong>"),             // 그 합계가 전체 반품 건수다
                        containsString("<td>미분류</td>"),               // 표 안에서도 드러난다
                        containsString("category=UNCLASSIFIED"),        // 미분류도 나머지 넷과 같은 링크로 열린다
                        containsString("관찰 경과: 기간 종료일로부터 5일"),   // 코호트 미성숙 경고 — 관찰일수 숨기지 않는다
                        containsString("주문 연결 불가 출고 <strong>2</strong>건은 분모에서 빠졌습니다"))));  // 분모 제외분 숨기지 않는다
    }

    // from > to면 서비스가 IllegalArgumentException을 던지고 컨트롤러는 report/breakdown에 null을 넣는다.
    // 템플릿의 th:if null 가드만이 그 자리에서 500이 나는 걸 막아준다 — 그 가드를 지키는 테스트가 없었다.
    @Test
    void 반품리포트_기간이_뒤집히면_500이_아니라_에러메시지를_렌더링한다() throws Exception {
        when(returnAnalyticsService.productReturnRates(any(), any()))
                .thenThrow(new IllegalArgumentException("시작일이 종료일보다 뒤입니다."));

        mockMvc.perform(get("/admin/returns/report")
                        .param("from", "2026-03-31").param("to", "2026-03-01")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/return-report"))
                .andExpect(content().string(containsString("시작일이 종료일보다 뒤입니다.")));
    }

    // 미분류가 0이면 행을 내지 않는다. 없는 것을 0으로 적어두면 읽는 사람이 매번
    // "이건 뭐지"를 한 번씩 거친다 — 표는 지금 있는 것만 말해야 한다.
    @Test
    void 반품리포트_미분류가_0이면_행을_내지_않는다() throws Exception {
        when(returnAnalyticsService.productReturnRates(any(), any())).thenReturn(
                new ReturnAnalyticsService.ReturnRateReport(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 5, List.of(), 0));
        when(returnAnalyticsService.categoryBreakdown(any(), any())).thenReturn(
                new ReturnAnalyticsService.CategoryBreakdown(
                        List.of(new ReturnAnalyticsService.CategoryCount(
                                ReturnCategory.WRONG_ITEM, ReturnOwnerArea.PICKING, 3)), 0, 3));

        mockMvc.perform(get("/admin/returns/report").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<td>미분류</td>"))));
    }

    // 기간을 매번 손으로 넣게 하면 아무도 안 본다. 기본값이 있어야 링크 한 번으로 열린다.
    @Test
    void 반품리포트_기간을_안_주면_최근_30일이_기본이다() throws Exception {
        when(returnAnalyticsService.productReturnRates(any(), any())).thenReturn(
                new ReturnAnalyticsService.ReturnRateReport(
                        LocalDate.now().minusDays(30), LocalDate.now(), 0, List.of(), 0));
        when(returnAnalyticsService.categoryBreakdown(any(), any())).thenReturn(
                new ReturnAnalyticsService.CategoryBreakdown(List.of(), 0, 0));

        mockMvc.perform(get("/admin/returns/report").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk());

        verify(returnAnalyticsService).productReturnRates(LocalDate.now().minusDays(30), LocalDate.now());
    }
    @Test
    void 반품상세_상품_축으로_열면_사유와_신뢰도가_렌더링된다() throws Exception {
        when(returnAnalyticsService.detailsByProduct(eq(11L), any(), any())).thenReturn(
                List.of(new ReturnAnalyticsService.ReturnDetailRow(
                        552L, 70000L, 11L, "상품 11", 2, "다른 색상이 왔어요",
                        ReturnCategory.WRONG_ITEM, Confidence.HIGH)));

        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("productId", "11")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/return-detail-list"))
                .andExpect(content().string(allOf(
                        containsString("다른 색상이 왔어요"),
                        containsString("오배송"),
                        containsString("높음"),
                        containsString("/admin/returns/552"))));   // 검수로 이어지는 링크
    }

    // 미분류는 범주 표의 다섯 번째 행이고 링크 모양이 나머지 넷과 같아야 한다.
    // 컨트롤러가 UNCLASSIFIED를 null로 바꿔 넘기는지가 이 테스트의 핵심이다.
    @Test
    void 반품상세_미분류로_열면_범주_없이_렌더링된다() throws Exception {
        when(returnAnalyticsService.detailsByCategory(isNull(), any(), any())).thenReturn(
                List.of(new ReturnAnalyticsService.ReturnDetailRow(
                        1L, 152L, 2L, "상품 2", 1, "test", null, null)));

        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("category", "UNCLASSIFIED")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("미분류"),
                        containsString("test"))));

        verify(returnAnalyticsService).detailsByCategory(isNull(),
                eq(LocalDate.of(2026, 3, 1)), eq(LocalDate.of(2026, 3, 31)));
    }

    // 축 없이 열리는 경우 — 사용자가 URL을 잘라 붙이면 생긴다. 500이 아니라 안내여야 한다.
    @Test
    void 반품상세_축을_안_주면_500이_아니라_안내를_낸다() throws Exception {
        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상품이나 범주를 골라 주세요")));
    }

}
