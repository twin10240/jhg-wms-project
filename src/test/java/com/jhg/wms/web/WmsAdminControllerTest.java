package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.service.RmaService;
import com.jhg.wms.service.CycleCountService;
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
import java.util.regex.Pattern;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @MockitoBean CycleCountService cycleCountService;
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
                .andExpect(model().attribute("deliveryPendingCount", 1L))   // 출고됐고 배송 완료 미기록
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
    void 예약화면_배송대기_탭은_출고완료중_미배송만_남긴다() throws Exception {
        Reservation reserved = Reservation.reserve(10L, Map.of(1L, 1));
        Reservation pending = Reservation.reserve(20L, Map.of(1L, 1));
        pending.ship();
        pending.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        Reservation done = Reservation.reserve(30L, Map.of(1L, 1));
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
        Reservation shipped = Reservation.reserve(20L, Map.of(1L, 1));
        shipped.ship();
        shipped.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        when(inventoryService.findAllReservations()).thenReturn(List.of(shipped));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MOCK-20-20260827063000")));
    }

    @Test
    void 예약화면_배송완료_버튼은_출고완료_행에만_나온다() throws Exception {
        Reservation shipped = Reservation.reserve(20L, Map.of(1L, 1));
        shipped.ship();
        shipped.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        when(inventoryService.findAllReservations())
                .thenReturn(List.of(Reservation.reserve(10L, Map.of(1L, 1)), shipped));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/reservations/20/deliver")))
                .andExpect(content().string(not(containsString("/admin/reservations/10/deliver"))));
    }

    @Test
    void 예약화면_배송완료된_행은_시각과_재통지_버튼을_보여준다() throws Exception {
        Reservation delivered = Reservation.reserve(30L, Map.of(1L, 1));
        delivered.ship();
        delivered.issueShipment(java.time.Instant.parse("2026-08-27T06:30:00Z"));
        delivered.deliver(java.time.Instant.parse("2026-08-28T01:00:00Z"));
        when(inventoryService.findAllReservations()).thenReturn(List.of(delivered));

        mockMvc.perform(get("/admin/reservations").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("배송 완료 2026-08-28")))
                // 재통지 버튼은 남는다 — 최초 콜백이 실패했을 때의 유일한 복구 경로다.
                .andExpect(content().string(containsString("OMS 재통지")))
                .andExpect(content().string(containsString("/admin/reservations/30/deliver")));
    }

    @Test
    void 배송완료_처리는_서비스를_호출하고_예약화면으로_돌아간다() throws Exception {
        when(inventoryService.markDelivered(20L)).thenReturn(true);

        mockMvc.perform(post("/admin/reservations/20/deliver")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reservations"))
                .andExpect(flash().attribute("successMessage", "배송 완료 처리했습니다. (주문 #20)"));

        verify(inventoryService).markDelivered(20L);
    }

    @Test
    void 이미_배송완료된_주문의_재통지는_통지_문구로_안내한다() throws Exception {
        when(inventoryService.markDelivered(20L)).thenReturn(false);

        mockMvc.perform(post("/admin/reservations/20/deliver")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "OMS에 배송 완료를 다시 통지했습니다. (주문 #20)"));
    }

    @Test
    void 배송완료_실패하면_에러_플래시를_담는다() throws Exception {
        doThrow(new IllegalStateException("송장이 없어 배송 완료할 수 없습니다. orderId=20"))
                .when(inventoryService).markDelivered(20L);

        mockMvc.perform(post("/admin/reservations/20/deliver")
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
        when(inventoryService.findTransactions(eq(null), any()))
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
}
