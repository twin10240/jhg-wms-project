package com.jhg.wms.domain;

/**
 * 반품 사유 범주.
 *
 * 라벨을 여기 둔다. 화면마다 매핑을 따로 두면 갈라진다 — 실제로 반품 상세 화면과
 * 리포트 화면이 같은 enum을 각자 옮기고 있었고, 한쪽은 한글, 한쪽은 enum 원문이었다.
 */
public enum ReturnCategory {

    DAMAGED("파손"),
    WRONG_ITEM("오배송"),
    CHANGED_MIND("변심"),
    OTHER("기타");

    private final String label;

    ReturnCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
