package com.jhg.wms.web;

import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaReturnItem;

import java.util.List;

public record RmaResponse(
        Long rmaId,
        String requestKey,
        Long orderId,
        String status,
        List<ItemResponse> items
) {
    public record ItemResponse(
            Long orderItemId,
            Long productId,
            int requestedQuantity,
            int acceptedQuantity,
            String disposition
    ) {}

    public static RmaResponse from(RmaReturn rma) {
        return new RmaResponse(
                rma.getId(),
                rma.getRequestKey(),
                rma.getOrderId(),
                rma.getStatus().name(),
                rma.getItems().stream().map(RmaResponse::toItemResponse).toList()
        );
    }

    private static ItemResponse toItemResponse(RmaReturnItem item) {
        return new ItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getRequestedQuantity(),
                item.getAcceptedQuantity() != null ? item.getAcceptedQuantity() : 0,
                item.getDisposition() != null ? item.getDisposition().name() : null
        );
    }
}
