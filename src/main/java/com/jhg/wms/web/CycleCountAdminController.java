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
        // 같은 요청 안에서 재고 스냅샷을 두 번 뜨지 않는다 — 두 번 부르면 그 사이 재고가 바뀔 경우
        // productNames와 bookQtyNow가 서로 다른 시점의 스냅샷이 될 수 있다.
        List<InventoryRowResponse> rows = inventoryService.findAllRows();
        model.addAttribute("cc", session);
        model.addAttribute("productNames", rows.stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        model.addAttribute("bookQtyNow", rows.stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::onHandQty)));
        // 반영된 차이는 원장에서 읽는다 — 세션에 복사해두지 않는다
        model.addAttribute("appliedDeltas", cycleCountService.appliedDeltas(id));
        return "admin/cycle-count-detail";
    }

    // 화면에는 "저장"과 "저장 후 제출"(action=submit) 두 버튼만 있고, 둘 다 이 한 폼(ccCounts)을 낸다.
    // 그래서 제출이 화면에 남은 미저장 값을 건너뛰고 예전 값으로 확정되는 경로가 애초에 없다.
    @PostMapping("/admin/cycle-counts/{id}/counts")
    public String saveCounts(@PathVariable Long id, @ModelAttribute CountForm form,
                             @RequestParam(required = false) String action, RedirectAttributes ra) {
        try {
            boolean submitting = "submit".equals(action);
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (var item : form.getItems()) {
                if (item.getCountedQty() == null) {
                    // 저장만 할 때는 빈 칸을 건너뛴다 — 나눠 세는 것을 허용하기 위해서다.
                    // 제출은 다르다. 이미 저장된 값이 있는 칸을 비우고 제출하면 화면은 비어 있는데
                    // 예전 값으로 확정되고, 제출은 되돌릴 수 없다.
                    if (submitting)
                        throw new IllegalArgumentException("모든 품목의 실물 수량을 입력해야 합니다.");
                    continue;
                }
                counts.put(item.getItemId(), item.getCountedQty());
            }
            cycleCountService.saveCounts(id, counts);
            if (submitting) {
                cycleCountService.submit(id);
                ra.addFlashAttribute("successMessage", "실물 수량을 저장하고 제출했습니다. 승인 대기 상태입니다.");
            } else {
                ra.addFlashAttribute("successMessage", "실물 수량을 저장했습니다.");
            }
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
