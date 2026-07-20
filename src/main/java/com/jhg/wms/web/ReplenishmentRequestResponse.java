package com.jhg.wms.web;

import com.jhg.wms.domain.ReplenishmentRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReplenishmentRequestResponse(Long id, UUID requestKey, String reason, String status,
        LocalDateTime requestedAt, LocalDateTime decidedAt, LocalDateTime fulfilledAt,
        String wmsMemo, Long purchaseOrderId, List<Item> items) {

    public record Item(Long productId, int requestedQty) {}

    public static ReplenishmentRequestResponse from(ReplenishmentRequest request) {
        return new ReplenishmentRequestResponse(
                request.getId(), request.getRequestKey(), request.getReason(), request.getStatus().name(),
                request.getRequestedAt(), request.getDecidedAt(), request.getFulfilledAt(),
                request.getWmsMemo(), request.getPurchaseOrderId(),
                request.getItems().stream()
                        .map(item -> new Item(item.getProductId(), item.getRequestedQty()))
                        .toList());
    }
}
