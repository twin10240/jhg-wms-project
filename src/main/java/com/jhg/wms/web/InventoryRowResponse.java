package com.jhg.wms.web;

public record InventoryRowResponse(Long productId, String productName, int onHandQty, int reservedQty, int availableQty) {}
