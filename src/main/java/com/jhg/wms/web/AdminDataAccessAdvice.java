package com.jhg.wms.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 화면에서 DB 계층 예외가 흰 500 페이지로 새는 것을 막는다.
 * 업무 예외(IllegalArgument/IllegalState)는 각 핸들러가 이미 flash로 처리하므로 여기 오지 않는다.
 * API 컨트롤러(@RestController)는 JSON 응답을 유지해야 하므로 대상에서 제외한다.
 */
@Slf4j
@ControllerAdvice(assignableTypes = {WmsAdminController.class, RmaAdminController.class})
public class AdminDataAccessAdvice {

    @ExceptionHandler(DataAccessException.class)
    public String handleDataAccess(DataAccessException e, HttpServletRequest request,
                                   RedirectAttributes ra) {
        // 스택은 반드시 남긴다 — 사용자에게 감추는 것이지 없던 일로 만드는 게 아니다.
        log.error("관리자 화면 DB 오류: {} {}", request.getMethod(), request.getRequestURI(), e);
        ra.addFlashAttribute("errorMessage", "저장 중 오류가 발생했습니다. 처리되지 않았습니다.");
        return "redirect:" + sectionOf(request.getRequestURI());
    }

    /** Referer를 쓰면 오픈 리다이렉트가 되므로, 요청 URI 앞 두 조각으로 목록 화면을 만든다. */
    private static String sectionOf(String uri) {
        String[] parts = uri.split("/");           // "/admin/returns/1/complete" → ["", "admin", "returns", ...]
        if (parts.length < 3) return "/";
        return "/" + parts[1] + "/" + parts[2];    // → "/admin/returns"
    }
}
