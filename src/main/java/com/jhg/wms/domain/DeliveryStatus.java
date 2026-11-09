package com.jhg.wms.domain;

/**
 * 배송 시도의 현재 상태. {@link ReservationStatus}와 별개다 — 실패·재배송·완료 중에도 예약은 SHIPPED로 남는다.
 * 값 이름이 OMS 콜백·송장 조회의 {@code status}로 그대로 나간다(외부 계약).
 */
public enum DeliveryStatus { SHIPPED, DELIVERY_FAILED, DELIVERED }
