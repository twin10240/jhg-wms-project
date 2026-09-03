package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.jhg.wms.support.OrderKeys.keyOf;

class ReservationTest {

    @Test
    void reserve_생성시_RESERVED_상태고_수량_원장을_보관한다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3, 20L, 5));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getOrderId()).isEqualTo(1L);
        assertThat(r.getQtyByProductId()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 3, 20L, 5));
    }

    @Test
    void ship_상태가_SHIPPED로_전이된다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void release_상태가_RELEASED로_전이된다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.release();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void deliver_배송완료_시각을_마이크로초로_잘라_담는다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        // Linux의 Instant.now()는 나노초까지 준다 — delivered_at은 timestamp(6)이라 DB 왕복에서 잘린다.
        r.deliver(Instant.parse("2026-08-27T06:30:00.123456789Z"));
        assertThat(r.getDeliveredAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
    }

    @Test
    void deliver_이전에는_배송완료_시각이_비어있다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        assertThat(r.getDeliveredAt()).isNull();
    }

    @Test
    void issueShipment_송장번호는_MOCK과_주문번호와_UTC_시각과_requestKey로_만든다() {
        Reservation r = Reservation.reserve(keyOf(202L), 202L, Map.of(3L, 1));

        // 2026-08-27T06:30:00Z — KST로는 15:30이다. 로컬 시각이 새어 들어오면 이 단언이 깨진다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00Z"));

        // 끝의 8자는 requestKey 앞자리 — 재사용된 orderId가 같은 초에 출고돼도 번호가 갈린다.
        assertThat(r.getTrackingNumber()).isEqualTo("MOCK-202-20260827063000-00000000");
        assertThat(r.getCarrierCode()).isEqualTo("MOCK");
        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00Z"));
    }

    @Test
    void issueShipment_issuedAt은_마이크로초로_잘려_저장된다() {
        Reservation r = Reservation.reserve(keyOf(203L), 203L, Map.of(3L, 1));

        // issued_at 컬럼은 timestamp(6) — 마이크로초까지만 저장된다. Linux(CI)의 Instant.now()는
        // 나노초 정밀도를 갖지만 macOS는 우연히 마이크로초 배수만 나와 이 차이가 로컬에서는 재현되지 않는다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00.123456789Z"));

        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
    }

    @Test
    void 예약_직후에는_송장이_없다() {
        Reservation r = Reservation.reserve(keyOf(202L), 202L, Map.of(3L, 1));

        assertThat(r.getTrackingNumber()).isNull();
        assertThat(r.getCarrierCode()).isNull();
        assertThat(r.getIssuedAt()).isNull();
    }
}
