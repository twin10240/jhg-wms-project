package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;

import java.util.List;

public record PurchaseOrderRequest(List<LineItem> lines, String memo) {
    public record LineItem(Long productId, int quantity) {}

    public List<PurchaseOrderLine> toServiceLines() {
        return lines.stream().map(l -> new PurchaseOrderLine(l.productId(), l.quantity())).toList();
    }
}
