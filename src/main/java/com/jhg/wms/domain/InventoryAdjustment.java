package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 수동 재고 조정 원장(insert-only). 발주 입고 같은 자동 증가는 기록하지 않는다 — 추후 전체 재고 트랜잭션 원장으로 확장. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryAdjustment {

    @Id @GeneratedValue
    @Column(name = "inventory_adjustment_id")
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false)
    private int beforeQty;

    @Column(nullable = false)
    private int afterQty;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static InventoryAdjustment of(Long productId, int delta, int beforeQty, int afterQty, String reason) {
        InventoryAdjustment a = new InventoryAdjustment();
        a.productId = productId;
        a.delta = delta;
        a.beforeQty = beforeQty;
        a.afterQty = afterQty;
        a.reason = reason;
        a.createdAt = LocalDateTime.now();
        return a;
    }
}
