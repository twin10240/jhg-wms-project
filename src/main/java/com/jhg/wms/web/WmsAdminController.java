package com.jhg.wms.web;

import com.jhg.wms.domain.*;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.service.ReturnAnalyticsService;
import com.jhg.wms.service.RmaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class WmsAdminController {

    private final InventoryService inventoryService;
    private final PurchaseOrderService purchaseOrderService;
    private final ReplenishmentRequestService replenishmentRequestService;
    private final RmaService rmaService;
    private final CycleCountService cycleCountService;
    private final ReturnAnalyticsService returnAnalyticsService;

    @GetMapping("/")
    public String dashboard(Model model) {
        List<InventoryRowResponse> rows = inventoryService.findAllRows();
        model.addAttribute("skuCount", rows.size());
        model.addAttribute("totalOnHand", rows.stream().mapToInt(InventoryRowResponse::onHandQty).sum());
        model.addAttribute("totalReserved", rows.stream().mapToInt(InventoryRowResponse::reservedQty).sum());
        model.addAttribute("totalAvailable", rows.stream().mapToInt(InventoryRowResponse::availableQty).sum());
        model.addAttribute("zeroAvailableCount", rows.stream().filter(r -> r.availableQty() == 0).count());

        List<PurchaseOrder> pos = purchaseOrderService.findAllWithItems();
        model.addAttribute("orderedPoCount", pos.stream()
                .filter(po -> po.getStatus() == PurchaseOrderStatus.ORDERED).count());
        model.addAttribute("partialPoCount", pos.stream()
                .filter(po -> po.getStatus() == PurchaseOrderStatus.PARTIALLY_RECEIVED).count());
        model.addAttribute("pendingRequestCount", replenishmentRequestService.findAll().stream()
                .filter(r -> r.getStatus() == ReplenishmentRequestStatus.REQUESTED).count());
        List<Reservation> reservations = inventoryService.findAllReservations();
        Map<ReservationStatus, Long> resCounts = reservations.stream()
                .collect(Collectors.groupingBy(Reservation::getStatus, Collectors.counting()));
        model.addAttribute("deliveryPendingCount", reservations.stream().filter(PENDING_DELIVERY).count());
        model.addAttribute("reservedCount", resCounts.getOrDefault(ReservationStatus.RESERVED, 0L));
        model.addAttribute("shippedCount", resCounts.getOrDefault(ReservationStatus.SHIPPED, 0L));
        model.addAttribute("releasedCount", resCounts.getOrDefault(ReservationStatus.RELEASED, 0L));
        Map<RmaStatus, Long> rmaCounts = rmaService.findAll(null).stream()
                .collect(Collectors.groupingBy(RmaReturn::getStatus, Collectors.counting()));
        model.addAttribute("rmaRequestedCount", rmaCounts.getOrDefault(RmaStatus.REQUESTED, 0L));
        model.addAttribute("rmaReceivedCount", rmaCounts.getOrDefault(RmaStatus.RECEIVED, 0L));
        model.addAttribute("rmaCompletedCount", rmaCounts.getOrDefault(RmaStatus.COMPLETED, 0L));
        model.addAttribute("pendingCycleCountCount", cycleCountService.countPendingApproval());
        return "admin/dashboard";
    }

    /** 배송 대기 = 출고됐고 아직 배송 완료를 기록하지 않은 예약. 창고가 손댈 행이 이것뿐이다. */
    private static final java.util.function.Predicate<Reservation> PENDING_DELIVERY =
            r -> r.getStatus() == ReservationStatus.SHIPPED && r.getDeliveredAt() == null;

    @GetMapping("/admin/reservations")
    public String reservations(@RequestParam(required = false) ReservationStatus status,
                               @RequestParam(defaultValue = "false") boolean pendingDelivery,
                               Model model) {
        List<Reservation> reservations = inventoryService.findAllReservations();
        // 배송 대기 탭은 상태 탭과 배타적이다 — 이미 SHIPPED로 좁혀진 목록이라 상태 필터를 겹칠 이유가 없다.
        if (pendingDelivery)
            reservations = reservations.stream().filter(PENDING_DELIVERY).toList();
        else if (status != null)
            reservations = reservations.stream().filter(r -> r.getStatus() == status).toList();
        model.addAttribute("reservations", reservations);
        model.addAttribute("activeStatus", status);
        model.addAttribute("pendingDelivery", pendingDelivery);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        return "admin/reservations";
    }

    /**
     * 배송 완료 처리. MANAGER 전용으로 두지 않은 이유는 재고 조정과 같다 —
     * 승인 성격의 "결정"이 아니라 현장이 관측한 사실의 기록이라 OPERATOR도 남길 수 있어야 한다.
     */
    @PostMapping("/admin/reservations/{orderId}/deliver")
    public String deliver(@PathVariable Long orderId, RedirectAttributes ra) {
        try {
            boolean firstTime = inventoryService.markDelivered(orderId);
            ra.addFlashAttribute("successMessage", firstTime
                    ? "배송 완료 처리했습니다. (주문 #" + orderId + ")"
                    : "OMS에 배송 완료를 다시 통지했습니다. (주문 #" + orderId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/reservations";
    }

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", inventoryService.findAllRows());
        return "admin/inventory";
    }

    @GetMapping("/admin/inventory/transactions")
    public String inventoryTransactions(@RequestParam(required = false) InventoryTransactionType type,
                                        @RequestParam(defaultValue = "0") int page,
                                        Model model) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, 20);
        var txnPage = inventoryService.findTransactions(type, pageable);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        model.addAttribute("transactions", txnPage.getContent());
        model.addAttribute("currentPage", txnPage.getNumber());
        model.addAttribute("totalPages", txnPage.getTotalPages());
        model.addAttribute("filterType", type);
        return "admin/inventory-transactions";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjust(@RequestParam Long productId, @RequestParam int delta,
                         @RequestParam(defaultValue = "") String reason,
                         RedirectAttributes ra) {
        try {
            cycleCountService.assertAdjustable(productId);   // 실사 중인 상품은 조정 거부
            int adjusted = inventoryService.adjust(productId, delta, reason);
            ra.addFlashAttribute("successMessage", "재고 조정 완료. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @GetMapping("/admin/inventory/ledger")
    public String ledger(@RequestParam(required = false) LocalDate from,
                         @RequestParam(required = false) LocalDate to,
                         Model model) {
        if (from == null) from = LocalDate.now().withDayOfMonth(1);
        if (to == null) to = LocalDate.now();
        try {
            List<InventoryService.LedgerRow> ledger = inventoryService.buildLedger(from, to);
            model.addAttribute("ledger", ledger);
            // 기간의 끝이 과거면 기말재고는 그 시점 값이라 현재 onHand와 달라야 정상이다.
            // 오늘까지 포함할 때만 불변식을 대조한다.
            boolean coversToday = !to.isBefore(LocalDate.now());
            model.addAttribute("invariantChecked", coversToday);
            model.addAttribute("invariantViolations",
                    coversToday ? inventoryService.findInvariantViolations(ledger) : List.of());
        } catch (IllegalArgumentException e) {
            model.addAttribute("ledger", List.of());
            model.addAttribute("invariantChecked", false);
            model.addAttribute("invariantViolations", List.of());
            model.addAttribute("errorMessage", e.getMessage());
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/inventory-ledger";
    }

    @GetMapping("/admin/returns/report")
    public String returnReport(@RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to,
                               Model model) {
        // 기간을 매번 손으로 넣게 하면 아무도 안 본다. 링크 한 번으로 열려야 한다.
        if (to == null) to = LocalDate.now();
        if (from == null) from = to.minusDays(30);
        try {
            model.addAttribute("report", returnAnalyticsService.productReturnRates(from, to));
            model.addAttribute("breakdown", returnAnalyticsService.categoryBreakdown(from, to));
        } catch (IllegalArgumentException e) {
            model.addAttribute("report", null);
            model.addAttribute("breakdown", null);
            model.addAttribute("errorMessage", e.getMessage());
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/return-report";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(@RequestParam(required = false) PurchaseOrderStatus status, Model model) {
        List<PurchaseOrder> pos = purchaseOrderService.findAllWithItems();
        if (status != null)
            pos = pos.stream().filter(po -> po.getStatus() == status).toList();
        model.addAttribute("purchaseOrders", pos);
        model.addAttribute("activeStatus", status);
        List<InventoryRowResponse> rows = inventoryService.findAllRows();
        model.addAttribute("products", rows);
        model.addAttribute("productNames", rows.stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        return "admin/purchaseorders";
    }

    @PostMapping("/admin/purchase-orders")
    public String createPo(@ModelAttribute PurchaseOrderForm form, RedirectAttributes ra) {
        List<PurchaseOrderLine> lines = form.getItems().stream()
                .map(i -> new PurchaseOrderLine(i.getProductId(), i.getQuantity()))
                .toList();
        try {
            Long poId = purchaseOrderService.create(lines, form.getMemo());
            ra.addFlashAttribute("successMessage", "발주 생성 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }

    @GetMapping("/admin/purchase-orders/{poId}")
    public String purchaseOrderDetail(@PathVariable Long poId, Model model) {
        model.addAttribute("po", purchaseOrderService.findWithItems(poId));
        return "admin/purchaseorderdetail";
    }

    @PostMapping("/admin/purchase-orders/{poId}/receive")
    public String receive(@PathVariable Long poId, @ModelAttribute ReceiveForm form, RedirectAttributes ra) {
        Map<Long, Integer> qtyByItemId = new LinkedHashMap<>();
        form.getItems().forEach(item -> qtyByItemId.merge(item.getItemId(), item.getQuantity(), Integer::sum));
        try {
            purchaseOrderService.receive(poId, qtyByItemId);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders/" + poId;
    }

    @PostMapping("/admin/purchase-orders/{poId}/cancel")
    public String cancelPurchaseOrder(@PathVariable Long poId, RedirectAttributes ra) {
        try {
            purchaseOrderService.cancel(poId);
            ra.addFlashAttribute("successMessage", "발주 취소 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders/" + poId;
    }

    @GetMapping("/admin/replenishment-requests")
    public String replenishmentRequests(Model model) {
        model.addAttribute("requests", replenishmentRequestService.findAll());
        return "admin/replenishmentrequests";
    }

    @PostMapping("/admin/replenishment-requests/{id}/approve")
    public String approveReplenishmentRequest(@PathVariable Long id,
                                              @RequestParam(defaultValue = "") String wmsMemo,
                                              RedirectAttributes ra) {
        try {
            replenishmentRequestService.approve(id, wmsMemo);
            ra.addFlashAttribute("successMessage", "보충 요청을 승인했습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/replenishment-requests";
    }

    @PostMapping("/admin/replenishment-requests/{id}/reject")
    public String rejectReplenishmentRequest(@PathVariable Long id,
                                             @RequestParam(defaultValue = "") String wmsMemo,
                                             RedirectAttributes ra) {
        try {
            replenishmentRequestService.reject(id, wmsMemo);
            ra.addFlashAttribute("successMessage", "보충 요청을 반려했습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/replenishment-requests";
    }
}
