package com.jhg.wms.web;

import java.util.List;

public record CreateRmaRequest(
        String requestKey,
        Long orderId,
        String reason,
        List<Item> items
) {
    public record Item(Long orderItemId, Long productId, int quantity) {}
}
