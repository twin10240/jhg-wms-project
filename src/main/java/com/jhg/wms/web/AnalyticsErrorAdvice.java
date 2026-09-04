package com.jhg.wms.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

/**
 * {@code /api/analytics} 조회의 400 평문 계약. 세 컨트롤러가 같은 문구를 쓴다.
 *
 * <p>본문이 평문인 것은 기존 API 오류 계약이고, 그 소비자는 사람이 아니라 모델이다 —
 * "내가 인자를 잘못 줬다"를 알아볼 수 있어야 스스로 고친다.
 *
 * <p>{@code basePackages}가 아니라 {@code assignableTypes}인 것은 의도다. 같은 패키지의
 * 관리자 화면 컨트롤러는 뷰를 돌려주므로, 거기까지 평문 400으로 덮으면 화면이 깨진다.
 */
@RestControllerAdvice(assignableTypes = {
        ReturnAnalyticsController.class,
        CycleCountAnalyticsController.class,
        InventoryLedgerAnalyticsController.class})
public class AnalyticsErrorAdvice {

    /** 400이지 500이 아니다 — 역전된 기간이 여기로 온다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** 누락된 from·to를 기본 처리로 두면 평문 계약이 깨진다. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body("필수 파라미터 '" + e.getParameterName() + "'가 없습니다.");
    }

    /**
     * 날짜 형식 오류. ReturnAnalyticsController.detailsByProduct의 productId(Long)도
     * 이 핸들러로 온다 — 기대 타입을 보고 문구를 가른다. 상품 ID에 "YYYY-MM-DD여야 한다"고
     * 답하면 모델이 상품 ID 자리에 날짜를 넣는다(실기동 curl에서 잡힌 결함). 고치라는 말이
     * 틀리면 없느니만 못하다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() == LocalDate.class ? "YYYY-MM-DD 날짜" : "정수";
        return ResponseEntity.badRequest()
                .body("파라미터 '" + e.getName() + "'의 형식이 올바르지 않습니다. " + expected + " 형식이어야 합니다.");
    }
}
