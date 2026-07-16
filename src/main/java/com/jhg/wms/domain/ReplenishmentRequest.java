package com.jhg.wms.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "replenishment_request", uniqueConstraints = {
        @UniqueConstraint(name = "uk_replenishment_request_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uk_replenishment_request_purchase_order", columnNames = "purchase_order_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequest {

    @Id @GeneratedValue
    @Column(name = "replenishment_request_id")
    private Long id;

    @Column(name = "request_key", nullable = false, unique = true)
    private UUID requestKey;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplenishmentRequestStatus status;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;
    private LocalDateTime fulfilledAt;
    private String wmsMemo;

    @Column(name = "purchase_order_id", unique = true)
    private Long purchaseOrderId;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReplenishmentRequestItem> items = new ArrayList<>();

    public static ReplenishmentRequest create(UUID requestKey, String reason,
                                              ReplenishmentRequestItem... items) {
        if (requestKey == null)
            throw new IllegalArgumentException("requestKey is required");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("reason is required");
        if (items == null || items.length == 0)
            throw new IllegalArgumentException("items are required");

        ReplenishmentRequest request = new ReplenishmentRequest();
        request.requestKey = requestKey;
        request.reason = reason.trim();
        request.status = ReplenishmentRequestStatus.REQUESTED;
        request.requestedAt = LocalDateTime.now();

        HashSet<Long> productIds = new HashSet<>();
        for (ReplenishmentRequestItem item : items) {
            if (item == null || !productIds.add(item.getProductId()))
                throw new IllegalArgumentException("items must have unique products");
            request.items.add(item);
            item.setRequest(request);
        }
        return request;
    }

    public void approve(Long purchaseOrderId, String memo) {
        requireStatus(ReplenishmentRequestStatus.REQUESTED);
        if (purchaseOrderId == null)
            throw new IllegalArgumentException("purchaseOrderId is required");
        this.status = ReplenishmentRequestStatus.APPROVED;
        this.decidedAt = LocalDateTime.now();
        this.wmsMemo = trim(memo);
        this.purchaseOrderId = purchaseOrderId;
    }

    public void reject(String memo) {
        requireStatus(ReplenishmentRequestStatus.REQUESTED);
        if (memo == null || memo.isBlank())
            throw new IllegalArgumentException("memo is required");
        this.status = ReplenishmentRequestStatus.REJECTED;
        this.decidedAt = LocalDateTime.now();
        this.wmsMemo = memo.trim();
    }

    public void fulfill() {
        requireStatus(ReplenishmentRequestStatus.APPROVED);
        this.status = ReplenishmentRequestStatus.FULFILLED;
        this.fulfilledAt = LocalDateTime.now();
    }

    private void requireStatus(ReplenishmentRequestStatus expected) {
        if (status != expected)
            throw new IllegalStateException("Expected status " + expected + " but was " + status);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
