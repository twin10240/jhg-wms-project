package com.jhg.wms.web;

import com.jhg.wms.service.ReplenishmentRequestService.RequestLine;

import java.util.List;
import java.util.UUID;

public record ReplenishmentRequestPayload(UUID requestKey, String reason, List<Item> items) {
    public record Item(Long productId, int requestedQty) {}

    public List<RequestLine> toServiceLines() {
        return items == null ? null : items.stream()
                .map(item -> item == null ? null : new RequestLine(item.productId(), item.requestedQty()))
                .toList();
    }
}
