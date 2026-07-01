package com.jhg.wms.web;

import java.util.Map;

public record InventoryWriteRequest(Long orderId, Map<Long, Integer> items) {}
