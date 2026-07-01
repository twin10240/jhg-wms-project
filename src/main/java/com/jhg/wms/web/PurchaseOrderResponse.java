package com.jhg.wms.web;

import com.jhg.wms.domain.PurchaseOrder;

import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id, String status, String memo,
        LocalDateTime createdAt, LocalDateTime receivedAt,
        List<ItemResponse> items
) {
    public record ItemResponse(Long id, Long productId, int quantity) {}

    public static PurchaseOrderResponse from(PurchaseOrder po) {
        return new PurchaseOrderResponse(
                po.getId(), po.getStatus().name(), po.getMemo(),
                po.getCreatedAt(), po.getReceivedAt(),
                po.getItems().stream()
                        .map(i -> new ItemResponse(i.getId(), i.getProductId(), i.getQuantity()))
                        .toList()
        );
    }
}
