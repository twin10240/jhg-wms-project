package com.jhg.wms.web;

import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.service.ReturnAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

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
 *
 * 소비자: mcp-server/wms_mcp/client.py가 이 넷의 URL 리터럴과 from·to 파라미터 이름을
 * 그대로 하드코딩해 부른다. 여기서 경로·파라미터 이름을 바꾸면 그쪽도 같이 고쳐야 한다
 * (Java 테스트는 이 불일치를 잡지 못한다).
 *
 * <p>400 평문 오류 계약은 {@link AnalyticsErrorAdvice}가 담당한다.
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

    @GetMapping("/return-details/product/{productId}")
    public List<ReturnAnalyticsService.ReturnDetailRow> detailsByProduct(
            @PathVariable Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.detailsByProduct(productId, from, to);
    }

    /**
     * UNCLASSIFIED는 ReturnCategory enum에 없는 값이다. 그래도 이 이름으로 받는 이유는
     * 범주 다섯 칸이 전부 같은 URL 모양으로 열리게 하기 위해서다 — 미분류만 다른 경로를
     * 쓰면 호출자가 분기를 하나 더 가져야 하고, 그 분기가 조용히 어긋난다.
     * 화면(WmsAdminController.returnReportDetail)이 같은 규약을 쓴다.
     */
    @GetMapping("/return-details/category/{category}")
    public List<ReturnAnalyticsService.ReturnDetailRow> detailsByCategory(
            @PathVariable String category,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReturnCategory parsed = "UNCLASSIFIED".equals(category) ? null : parseCategory(category);
        return returnAnalyticsService.detailsByCategory(parsed, from, to);
    }

    // ReturnCategory.valueOf의 기본 메시지("No enum constant com.jhg...")는 FQCN을 흘린다.
    // 받는 값 다섯 개를 이름으로 준다 — 모델이 여기서 바로 고칠 수 있어야 한다.
    private static ReturnCategory parseCategory(String category) {
        try {
            return ReturnCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "알 수 없는 category '" + category
                            + "'. 다음 중 하나여야 합니다: DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER, UNCLASSIFIED");
        }
    }
}
