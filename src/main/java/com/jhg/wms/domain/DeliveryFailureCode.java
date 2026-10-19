package com.jhg.wms.domain;

/** 배송 실패 사유. 자유 서술은 받지 않는다 — 값 이름이 OMS 콜백의 {@code failureCode}로 그대로 나간다. */
public enum DeliveryFailureCode {
    RECIPIENT_UNAVAILABLE("수취인 부재"),
    ADDRESS_ISSUE("주소 문제");

    private final String label;

    DeliveryFailureCode(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 폼 입력 해석. 빈 값·미지원 값은 enum 바인딩 400 대신 화면 오류로 돌려주려고 직접 받는다. */
    public static DeliveryFailureCode parse(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("배송 실패 사유를 선택하세요.");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 배송 실패 사유입니다: " + value);
        }
    }
}
