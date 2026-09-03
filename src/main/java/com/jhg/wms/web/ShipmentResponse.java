package com.jhg.wms.web;

import com.jhg.wms.domain.Reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * 송장 조회 응답. OMS가 자기 쪽 송장·배송 상태와 대조해 불일치를 복구할 때 쓴다.
 *
 * <p>출고 응답(`ShipResponse`)과 필드가 겹치지만 합치지 않는다 — 출고 응답에 `deliveredAt`을 더하면
 * 이미 OMS가 파싱 중인 `POST /api/inventory/ship` 계약이 바뀐다. 조회는 배송 상태까지 보여줘야 하고
 * 출고 시점에는 항상 null이라, 두 계약의 수명이 다르다.
 *
 * <p><b>`trackingNumber`를 키로 쓰지 말 것</b> — 발급 시각이 들어가므로 WMS DB가 초기화되면 같은
 * 주문이 다른 번호를 받는다. 상관관계는 `requestKey`로 잡는다(`ShipResponse`와 같은 규칙).
 */
public record ShipmentResponse(UUID requestKey, Long orderId, String carrierCode, String carrierName,
                               String trackingNumber, Instant issuedAt, Instant deliveredAt) {

    public static ShipmentResponse from(Reservation r) {
        return new ShipmentResponse(r.getRequestKey(), r.getOrderId(), r.getCarrierCode(), Reservation.CARRIER_NAME,
                r.getTrackingNumber(), r.getIssuedAt(), r.getDeliveredAt());
    }
}
