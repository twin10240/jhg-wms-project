package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem {

    @Id @GeneratedValue
    @Column(name = "purchase_order_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @Column(name = "product_id")
    private Long productId;

    private int quantity;

    private int receivedQty;   // 누적 입고량. 신규 생성 시 0.

    public static PurchaseOrderItem create(Long productId, int quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.productId = productId;
        item.quantity = quantity;
        return item;
    }

    public int remainingQty() {
        return quantity - receivedQty;
    }

    public boolean isFullyReceived() {
        return remainingQty() == 0;
    }

    /** 이번 입고분을 누적한다. 0은 "이번에 안 온 품목"이라 허용한다. */
    void receive(int qty) {
        validateReceivable(qty);
        this.receivedQty += qty;
    }

    void validateReceivable(int qty) {
        if (qty < 0)
            throw new IllegalArgumentException("상품#" + productId + ": 입고 수량은 0 이상이어야 합니다.");
        if (qty > remainingQty())
            throw new IllegalArgumentException(
                    "상품#" + productId + ": 잔량 " + remainingQty() + "개를 초과했습니다 (요청 " + qty + "개)");
    }

    void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }
}
