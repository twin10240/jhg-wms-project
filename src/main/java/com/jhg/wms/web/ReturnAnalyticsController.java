package com.jhg.wms.web;

import com.jhg.wms.service.ReturnAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 반품 분석 조회 REST. V6.0b의 Python MCP 서버가 이것을 부른다.
 *
 * 계산하지 않는다 — 서비스에 위임하고 레코드를 그대로 직렬화할 뿐이다. 여기에 집계를
 * 한 줄이라도 넣으면 화면과 보고서가 다른 숫자를 낼 수 있게 되고, 이 설계 전체가 그것을 막아왔다.
 *
 * 읽기 전용이다. 반품 사유는 고객이 쓴 자유 텍스트이고 그것이 모델 컨텍스트로 들어간다.
 * 쓰기 서비스를 여기 주입하면 고객이 창고 데이터를 건드릴 경로가 열린다.
 *
 * 경로가 /api/** 안인 것은 의도다 — apiChain이 basic 인증·CSRF 비활성·401 직접 응답을
 * 이미 갖고 있어 SecurityConfig를 고치지 않는다. 다른 접두사로 옮기면 폼 로그인 체인으로 떨어진다.
 *
 * from·to에 기본값을 두지 않는다. 화면은 "안 넣으면 최근 30일"이 친절하지만,
 * 보고서는 분모가 무엇인지 분명해야 한다.
 */
@RestController
@RequestMapping("/api/analytics")
public class ReturnAnalyticsController {

    private final ReturnAnalyticsService returnAnalyticsService;

    public ReturnAnalyticsController(ReturnAnalyticsService returnAnalyticsService) {
        this.returnAnalyticsService = returnAnalyticsService;
    }

    @GetMapping("/product-return-rates")
    public ReturnAnalyticsService.ReturnRateReport productReturnRates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.productReturnRates(from, to);
    }

    @GetMapping("/return-categories")
    public ReturnAnalyticsService.CategoryBreakdown returnCategories(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.categoryBreakdown(from, to);
    }
}
