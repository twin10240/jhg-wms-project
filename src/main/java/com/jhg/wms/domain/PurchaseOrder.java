package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 이번 입고분을 반영한다.
     * @param qtyByItemId 발주품목ID → 이번 입고량 (0 허용 — 이번에 안 온 품목)
     * @return 실제 반영된 productId → delta (0인 항목은 담기지 않고, 같은 상품은 합산된다)
     */
    public Map<Long, Integer> receive(Map<Long, Integer> qtyByItemId) {
        if (status == PurchaseOrderStatus.RECEIVED)
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");

        Map<Long, PurchaseOrderItem> byItemId = new LinkedHashMap<>();
        items.forEach(item -> byItemId.put(item.getId(), item));
        for (Long itemId : qtyByItemId.keySet())
            if (!byItemId.containsKey(itemId))
                throw new IllegalArgumentException("발주에 없는 품목입니다: itemId=" + itemId);

        // 상태를 바꾸기 전에 걸러낸다 — 빈 제출로 상태만 바뀌는 것을 막는다.
        if (qtyByItemId.values().stream().allMatch(qty -> qty == null || qty == 0))
            throw new IllegalArgumentException("입고 수량이 없습니다.");

        Map<Long, Integer> deltaByProductId = new LinkedHashMap<>();
        qtyByItemId.forEach((itemId, qty) -> {
            int received = qty == null ? 0 : qty;
            PurchaseOrderItem item = byItemId.get(itemId);
            item.receive(received);
            if (received > 0)
                deltaByProductId.merge(item.getProductId(), received, Integer::sum);
        });

        if (items.stream().allMatch(PurchaseOrderItem::isFullyReceived)) {
            this.status = PurchaseOrderStatus.RECEIVED;
            this.receivedAt = LocalDateTime.now();
        } else {
            this.status = PurchaseOrderStatus.PARTIALLY_RECEIVED;
        }
        return deltaByProductId;
    }
}
