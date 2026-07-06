package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

    @Id @GeneratedValue
    @Column(name = "purchase_order_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    private PurchaseOrderStatus status;

    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime receivedAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    public static PurchaseOrder create(String memo, PurchaseOrderItem... items) {
        PurchaseOrder po = new PurchaseOrder();
        po.memo = memo;
        po.status = PurchaseOrderStatus.ORDERED;
        po.createdAt = LocalDateTime.now();
        for (PurchaseOrderItem item : items) {
            po.items.add(item);
            item.setPurchaseOrder(po);
        }
        return po;
    }

    public void receive() {
        if (status == PurchaseOrderStatus.RECEIVED)
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");
        this.status = PurchaseOrderStatus.RECEIVED;
        this.receivedAt = LocalDateTime.now();
    }
}
