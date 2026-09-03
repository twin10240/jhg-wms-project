package com.jhg.wms.support;

import java.util.UUID;

/**
 * 테스트용 결정적 requestKey 생성기.
 *
 * <p>운영에서 requestKey는 OMS가 주문마다 새로 만드는 임의 UUID이지만, 기존 테스트 대부분은
 * "같은 orderId면 같은 예약"이라는 의미를 검증한다. orderId를 UUID로 1:1 사상해 그 의미를
 * 그대로 보존한다 — 테스트가 검증하던 동작은 바뀌지 않고 API만 새 키를 통과한다.
 *
 * <p>세대가 다른 같은 orderId(= 서로 다른 requestKey)는 이 헬퍼로 만들 수 없다.
 * 그 경우는 {@code ReservationRequestKeyTest}가 임의 UUID로 직접 검증한다.
 */
public final class OrderKeys {

    private OrderKeys() {}

    public static UUID keyOf(long orderId) {
        return new UUID(0L, orderId);
    }
}
