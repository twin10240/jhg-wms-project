package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id @GeneratedValue
    @Column(name = "inventory_id")
    private Long id;

    @Version
    private Long version;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int onHandQty = 0;

    @Column(nullable = false)
    private int reservedQty = 0;

    public static Inventory create(Long productId, int onHandQty) {
        Inventory inv = new Inventory();
        inv.productId = productId;
        inv.onHandQty = onHandQty;
        return inv;
    }

    /** 판매 가용 수량 = 실물(onHand) - 예약(reserved) */
    public int getAvailableQty() {
        return onHandQty - reservedQty;
    }
    // ponytail: reserve/ship/release는 S2에서 추가
}
