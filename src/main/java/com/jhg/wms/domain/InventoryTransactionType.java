package com.jhg.wms.domain;

public enum InventoryTransactionType {
    OPENING, // 초기 재고(시드·기존분 소급)
    RECEIVE, // 발주 입고
    SHIP,    // 출고
    ADJUST   // 수동 조정
}
