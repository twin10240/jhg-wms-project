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

    // productId당 재고 행은 하나뿐 — findByProductId가 Optional을 반환하는 전제(중복행이면 깨짐).
    @Column(name = "product_id", nullable = false, unique = true)
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

    /** 가용하면 예약하고 true, 부족하면 false(변경 없음). */
    public boolean reserve(int qty) {
        if (getAvailableQty() < qty) return false;
        reservedQty += qty;
        return true;
    }

    /** 예약분 출고: 실물·예약 동시 차감. */
    public void ship(int qty) {
        onHandQty -= qty;
        reservedQty -= qty;
    }

    /** 예약 해제: 예약분 복구. */
    public void release(int qty) {
        reservedQty -= qty;
    }
}
