package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @Test
    void reserve_생성시_RESERVED_상태고_수량_원장을_보관한다() {
        Reservation r = Reservation.reserve(1L, Map.of(10L, 3, 20L, 5));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getOrderId()).isEqualTo(1L);
        assertThat(r.getQtyByProductId()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 3, 20L, 5));
    }

    @Test
    void ship_상태가_SHIPPED로_전이된다() {
        Reservation r = Reservation.reserve(1L, Map.of(10L, 3));
        r.ship();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void release_상태가_RELEASED로_전이된다() {
        Reservation r = Reservation.reserve(1L, Map.of(10L, 3));
        r.release();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void issueShipment_송장번호는_MOCK과_주문번호와_UTC_시각으로_만든다() {
        Reservation r = Reservation.reserve(202L, Map.of(3L, 1));

        // 2026-08-27T06:30:00Z — KST로는 15:30이다. 로컬 시각이 새어 들어오면 이 단언이 깨진다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00Z"));

        assertThat(r.getTrackingNumber()).isEqualTo("MOCK-202-20260827063000");
        assertThat(r.getCarrierCode()).isEqualTo("MOCK");
        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00Z"));
    }

    @Test
    void 예약_직후에는_송장이_없다() {
        Reservation r = Reservation.reserve(202L, Map.of(3L, 1));

        assertThat(r.getTrackingNumber()).isNull();
        assertThat(r.getCarrierCode()).isNull();
        assertThat(r.getIssuedAt()).isNull();
    }
}
