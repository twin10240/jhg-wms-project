package com.jhg.wms.web;

public record InventoryRowResponse(Long productId, int onHandQty, int reservedQty, int availableQty) {}
