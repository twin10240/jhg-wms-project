package com.jhg.wms.web;

import com.jhg.wms.domain.Reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * 출고 응답. OMS가 송장 정보를 저장·표시한다.
 *
 * <p>status 필드는 두지 않는다 — 성공 응답은 항상 SHIPPED이고 나머지는 HTTP 오류라 중복이다.
 *
 * <p><b>trackingNumber를 키로 쓰지 말 것.</b> 발급 시각이 들어가므로 WMS DB가 초기화되면
 * 같은 주문이 다른 번호를 받는다. 상관관계는 {@code requestKey}로 잡는다 — {@code orderId}는
 * OMS DB가 초기화되면 재사용되므로 키가 아니라 사람이 읽는 참조다. 그래서 둘을 함께 싣는다.
 */
public record ShipResponse(UUID requestKey, Long orderId, String carrierCode, String carrierName,
                           String trackingNumber, Instant issuedAt) {

    public static ShipResponse from(Reservation r) {
        // 택배사가 MOCK 하나뿐이라 이름을 상수에서 유도한다. 늘어나면 code→name 매핑이 필요하다.
        return new ShipResponse(r.getRequestKey(), r.getOrderId(), r.getCarrierCode(), Reservation.CARRIER_NAME,
                r.getTrackingNumber(), r.getIssuedAt());
    }
}
