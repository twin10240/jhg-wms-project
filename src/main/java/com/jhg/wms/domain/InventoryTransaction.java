package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 재고 트랜잭션 원장(insert-only). onHand를 바꾸는 모든 경로가 한 줄씩 남긴다.
 *  ponytail: 테이블명은 inventory_adjustment 유지 — prod 크로스테이블 마이그레이션 회피(클래스만 승격). */
@Entity
@Table(name = "inventory_adjustment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryTransaction {

    @Id @GeneratedValue
    @Column(name = "inventory_adjustment_id")
    private Long id;

    @Column(nullable = false)
    private Long productId;

    // nullable: 기존 행(구 조정) 수용 — 기동 백필이 ADJUST로 채운다.
    @Enumerated(EnumType.STRING)
    private InventoryTransactionType type;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false)
    private int beforeQty;

    @Column(nullable = false)
    private int afterQty;

    // 추적용: "PO#12", "ORDER#34". OPENING·수동조정은 null.
    private String reference;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static InventoryTransaction of(Long productId, InventoryTransactionType type, int delta,
                                          int beforeQty, int afterQty, String reference, String reason) {
        InventoryTransaction t = new InventoryTransaction();
        t.productId = productId;
        t.type = type;
        t.delta = delta;
        t.beforeQty = beforeQty;
        t.afterQty = afterQty;
        t.reference = reference;
        t.reason = reason;
        t.createdAt = LocalDateTime.now();
        return t;
    }
}
