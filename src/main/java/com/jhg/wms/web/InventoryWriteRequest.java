package com.jhg.wms.web;

import java.util.Map;
import java.util.UUID;

/**
 * 재고 쓰기 요청(reserve/ship/release 공용).
 *
 * <p>{@code requestKey}는 OMS가 주문 생성 시 발급하는 UUID이고 <b>모든 경로에서 필수</b>다 —
 * 예약을 찾는 유일한 키다. {@code orderId}는 예약을 새로 만드는 reserve에서만 쓰이고
 * ship/release는 무시한다(예약 원장이 SSOT). 표시·추적용이라 유일하지 않다.
 */
public record InventoryWriteRequest(UUID requestKey, Long orderId, Map<Long, Integer> items) {}
