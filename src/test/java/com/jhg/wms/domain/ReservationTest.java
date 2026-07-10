package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

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
}
