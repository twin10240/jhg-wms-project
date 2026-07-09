package com.jhg.wms.web;

import com.jhg.wms.domain.*;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
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
    public String inventory(Model model) {
        model.addAttribute("products", inventoryService.findAllRows());
        return "admin/inventory";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjust(@RequestParam Long productId, @RequestParam int delta,
                         @RequestParam(defaultValue = "") String reason,
                         RedirectAttributes ra) {
        try {
            int adjusted = inventoryService.adjust(productId, delta);
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
            purchaseOrderService.receive(poId);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }
}
