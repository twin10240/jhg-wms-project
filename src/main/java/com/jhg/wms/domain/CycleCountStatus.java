package com.jhg.wms.domain;

public enum CycleCountStatus {
    OPEN,       // 작성 중 — 실물 수량 입력·수정 가능
    SUBMITTED,  // 승인 대기 — 계수 완료, 장부는 아직 그대로
    APPROVED,   // 승인 — 차이가 COUNT 원장으로 반영됨(종결)
    REJECTED    // 반려 — 장부 불변(종결)
}
