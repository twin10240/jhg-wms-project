package com.jhg.wms.web;

public record AdjustRequest(Long productId, int delta, String reason) {}
