package com.jhg.wms.web;

import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import com.jhg.wms.service.ReplenishmentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class WmsAdminController {

    private final InventoryService inventoryService;
    private final PurchaseOrderService purchaseOrderService;
    private final ReplenishmentRequestService replenishmentRequestService;

    @GetMapping("/")
    public String dashboard(Model model) {
        List<InventoryRowResponse> rows = inventoryService.findAllRows();
        model.addAttribute("skuCount", rows.size());
        model.addAttribute("totalOnHand", rows.stream().mapToInt(InventoryRowResponse::onHandQty).sum());
        model.addAttribute("totalReserved", rows.stream().mapToInt(InventoryRowResponse::reservedQty).sum());
        model.addAttribute("totalAvailable", rows.stream().mapToInt(InventoryRowResponse::availableQty).sum());
        model.addAttribute("orderedPoCount", purchaseOrderService.findAllWithItems().stream()
                .filter(po -> po.getStatus() == PurchaseOrderStatus.ORDERED).count());
        Map<ReservationStatus, Long> resCounts = inventoryService.findAllReservations().stream()
                .collect(Collectors.groupingBy(Reservation::getStatus, Collectors.counting()));
        model.addAttribute("reservedCount", resCounts.getOrDefault(ReservationStatus.RESERVED, 0L));
        model.addAttribute("shippedCount", resCounts.getOrDefault(ReservationStatus.SHIPPED, 0L));
        model.addAttribute("releasedCount", resCounts.getOrDefault(ReservationStatus.RELEASED, 0L));
        return "admin/dashboard";
    }

    @GetMapping("/admin/reservations")
    public String reservations(@RequestParam(required = false) ReservationStatus status, Model model) {
        List<Reservation> reservations = inventoryService.findAllReservations();
        if (status != null)
            reservations = reservations.stream().filter(r -> r.getStatus() == status).toList();
        model.addAttribute("reservations", reservations);
        model.addAttribute("activeStatus", status);
        return "admin/reservations";
    }

    @GetMapping("/admin/inventory")
    public String inventory(@RequestParam(required = false) InventoryTransactionType type, Model model) {
        model.addAttribute("products", inventoryService.findAllRows());
        model.addAttribute("transactions", inventoryService.findTransactions(type));
        model.addAttribute("filterType", type);
        return "admin/inventory";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjust(@RequestParam Long productId, @RequestParam int delta,
                         @RequestParam(defaultValue = "") String reason,
                         RedirectAttributes ra) {
        try {
            int adjusted = inventoryService.adjust(productId, delta, reason);
            ra.addFlashAttribute("successMessage", "재고 조정 완료. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(@RequestParam(required = false) PurchaseOrderStatus status, Model model) {
        List<PurchaseOrder> pos = purchaseOrderService.findAllWithItems();
        if (status != null)
            pos = pos.stream().filter(po -> po.getStatus() == status).toList();
        model.addAttribute("purchaseOrders", pos);
        model.addAttribute("activeStatus", status);
        model.addAttribute("products", inventoryService.findAllRows());
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

    @PostMapping("/admin/purchase-orders/receive")
    public String receive(@RequestParam Long poId, RedirectAttributes ra) {
        try {
            // TODO(Task 4): 부분 입고 UI로 교체. 지금은 전량 입고로 옛 동작을 유지하는 임시 다리.
            PurchaseOrder po = purchaseOrderService.findWithItems(poId);
            Map<Long, Integer> everything = new java.util.LinkedHashMap<>();
            po.getItems().forEach(item -> everything.put(item.getId(), item.remainingQty()));
            purchaseOrderService.receive(poId, everything);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
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
            ra.addFlashAttribute("successMessage", "Request approved.");
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
            ra.addFlashAttribute("successMessage", "Request rejected.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/replenishment-requests";
    }
}
