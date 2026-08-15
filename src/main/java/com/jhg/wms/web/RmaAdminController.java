package com.jhg.wms.web;

import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaStatus;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.RmaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class RmaAdminController {

    private final RmaService rmaService;
    private final InventoryService inventoryService;

    @GetMapping("/admin/returns")
    public String list(@RequestParam(required = false) RmaStatus status, Model model) {
        model.addAttribute("returns", rmaService.findAll(status));
        model.addAttribute("activeStatus", status);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        return "admin/returns";
    }

    @GetMapping("/admin/returns/{id}")
    public String detail(@PathVariable Long id, Model model) {
        RmaReturn rma = rmaService.findById(id);
        model.addAttribute("rma", rma);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        return "admin/returndetail";
    }

    @PostMapping("/admin/returns/{id}/receive")
    public String receive(@PathVariable Long id, RedirectAttributes ra) {
        try {
            rmaService.receive(id);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (RMA #" + id + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/admin/returns/{id}/complete")
    public String complete(@PathVariable Long id, @ModelAttribute InspectionForm form,
                           RedirectAttributes ra) {
        try {
            Map<Long, RmaService.InspectionResult> results = new LinkedHashMap<>();
            for (var item : form.getItems())
                results.put(item.getItemId(),
                        new RmaService.InspectionResult(item.getAcceptedQuantity(), item.getDisposition()));
            rmaService.complete(id, results);
            ra.addFlashAttribute("successMessage", "검수 완료. (RMA #" + id + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/admin/returns/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        try {
            rmaService.cancel(id);
            ra.addFlashAttribute("successMessage", "RMA 취소 완료. (RMA #" + id + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/returns/" + id;
    }
}
