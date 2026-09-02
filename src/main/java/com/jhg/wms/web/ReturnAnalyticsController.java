package com.jhg.wms.web;

import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.service.ReturnAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * 400이지 500이 아니다. 역전된 기간(서비스의 cohort())과 알 수 없는 범주 이름
     * (ReturnCategory.valueOf)이 여기로 온다.
     *
     * 이 구분이 MCP 서버에서 커진다 — 모델이 "내가 인자를 잘못 줬다"와 "창고가 고장났다"를
     * 구분할 수 있어야 스스로 고친다. 본문은 평문이다(README의 기존 API 오류 계약).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /**
     * 필수 파라미터 누락(from·to)은 기본적으로 DefaultHandlerExceptionResolver가 sendError로
     * 처리한다 — MockMvc에서는 빈 본문, 실배포에서는 서블릿 ERROR 재디스패치를 거쳐 Boot의
     * BasicErrorController가 JSON을 낸다. 둘 다 이 표면의 평문 계약을 어긴다. 여기서 직접 잡는다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body("필수 파라미터 '" + e.getParameterName() + "'가 없습니다.");
    }

    /**
     * 위와 같은 이유로 직접 잡는다. 날짜(from·to)와 상품 ID(productId)가 대상이다.
     *
     * 기대 타입을 보고 문구를 가른다 — 상품 ID에 "YYYY-MM-DD여야 한다"고 답하면
     * 모델이 상품 ID 자리에 날짜를 넣는다. 고치라는 말이 틀리면 없느니만 못하다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() == LocalDate.class ? "YYYY-MM-DD 날짜" : "정수";
        return ResponseEntity.badRequest()
                .body("파라미터 '" + e.getName() + "'의 형식이 올바르지 않습니다. " + expected + " 형식이어야 합니다.");
    }
}
