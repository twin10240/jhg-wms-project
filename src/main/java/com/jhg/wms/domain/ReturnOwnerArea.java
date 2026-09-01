package com.jhg.wms.domain;

/**
 * 반품 사유의 소관. 이 축이 보고서가 WMS에서 나와야 하는 이유다 —
 * 창고가 직접 통제할 수 있는 반품을 분리해내는 건 다른 시스템이 못 한다.
 *
 * 설정으로 빼지 않는다. 도메인 판단이지 취향이 아니다.
 *
 * "창고가 줄일 수 있나"를 boolean으로 넣지 않았다. DAMAGED는 포장 개선으로 줄 수도
 * 운송사 문제일 수도 있어 참·거짓 어느 쪽으로 접어도 총계가 거짓말을 한다.
 * 소관만 밝히고 판단은 읽는 사람에게 남긴다.
 */
public enum ReturnOwnerArea {

    PICKING("피킹·출고"),
    PACKAGING("포장·운송"),
    PRODUCT_INFO("상품 정보"),
    OUTSIDE("창고 밖");

    private final String label;

    ReturnOwnerArea(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ReturnOwnerArea of(ReturnCategory category) {
        return switch (category) {
            case WRONG_ITEM -> PICKING;
            case DAMAGED -> PACKAGING;
            case CHANGED_MIND -> PRODUCT_INFO;
            case OTHER -> OUTSIDE;
        };
    }
}
