package com.jhg.wms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "replenishment_request_item", uniqueConstraints =
        @UniqueConstraint(name = "uk_replenishment_request_item_product",
                columnNames = {"request_id", "product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequestItem {

    @Id @GeneratedValue
    @Column(name = "replenishment_request_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ReplenishmentRequest request;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int requestedQty;

    public static ReplenishmentRequestItem create(Long productId, int requestedQty) {
        if (productId == null)
            throw new IllegalArgumentException("productId is required");
        if (requestedQty < 1)
            throw new IllegalArgumentException("requestedQty must be at least 1");

        ReplenishmentRequestItem item = new ReplenishmentRequestItem();
        item.productId = productId;
        item.requestedQty = requestedQty;
        return item;
    }

    void setRequest(ReplenishmentRequest request) {
        this.request = request;
    }
}
