package com.jhg.wms.web;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class CycleCountAdminController {

    private final CycleCountService cycleCountService;
    private final InventoryService inventoryService;

    @GetMapping("/admin/cycle-counts")
    public String list(@RequestParam(required = false) CycleCountStatus status, Model model) {
        model.addAttribute("sessions", cycleCountService.findAll(status));
        model.addAttribute("activeStatus", status);
        return "admin/cycle-counts";
    }

    @GetMapping("/admin/cycle-counts/new")
    public String newForm(Model model) {
        model.addAttribute("rows", inventoryService.findAllRows());
        return "admin/cycle-count-new";
    }

    @PostMapping("/admin/cycle-counts")
    public String create(@RequestParam(required = false) List<Long> productIds,
                         @RequestParam(required = false) String memo,
                         RedirectAttributes ra) {
        try {
            CycleCount session = cycleCountService.open(productIds, memo);
            ra.addFlashAttribute("successMessage", "실사를 시작했습니다. (실사 #" + session.getId() + ")");
            return "redirect:/admin/cycle-counts/" + session.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/cycle-counts/new";
        }
    }

    @GetMapping("/admin/cycle-counts/{id}")
    public String detail(@PathVariable Long id, Model model) {
        CycleCount session = cycleCountService.findById(id);
        // 모델 속성명 "session"은 쓰지 않는다 — Thymeleaf WebEngineContext가 그 이름을
        // HttpSession 속성 맵으로 예약해 모델 속성을 가려버린다(널 조인으로 500).
        model.addAttribute("cc", session);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        model.addAttribute("bookQtyNow", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::onHandQty)));
        // 반영된 차이는 원장에서 읽는다 — 세션에 복사해두지 않는다
        model.addAttribute("appliedDeltas", cycleCountService.appliedDeltas(id));
        return "admin/cycle-count-detail";
    }

    @PostMapping("/admin/cycle-counts/{id}/counts")
    public String saveCounts(@PathVariable Long id, @ModelAttribute CountForm form, RedirectAttributes ra) {
        try {
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (var item : form.getItems())
                if (item.getCountedQty() != null)   // 미입력은 건너뛴다 — 부분 저장을 허용한다
                    counts.put(item.getItemId(), item.getCountedQty());
            cycleCountService.saveCounts(id, counts);
            ra.addFlashAttribute("successMessage", "실물 수량을 저장했습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/submit")
    public String submit(@PathVariable Long id, RedirectAttributes ra) {
        try {
            cycleCountService.submit(id);
            ra.addFlashAttribute("successMessage", "실사를 제출했습니다. 승인 대기 상태입니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes ra) {
        try {
            cycleCountService.approve(id);
            ra.addFlashAttribute("successMessage", "실사를 승인했습니다. 차이가 재고에 반영됐습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        try {
            cycleCountService.reject(id, reason);
            ra.addFlashAttribute("successMessage", "실사를 반려했습니다. 재고는 그대로입니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    // 없는 실사를 열면 흰 500이 나던 자리 — 상태 전이 핸들러는 각자 flash로 처리하므로 여기 오지 않는다.
    @ExceptionHandler(CycleCountService.CycleCountNotFoundException.class)
    public ModelAndView notFound(CycleCountService.CycleCountNotFoundException e) {
        ModelAndView mav = new ModelAndView("error", HttpStatus.NOT_FOUND);
        mav.addObject("status", HttpStatus.NOT_FOUND.value());
        mav.addObject("error", e.getMessage());
        return mav;
    }
}
