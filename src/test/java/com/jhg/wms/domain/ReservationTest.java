package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @Test
    void reserve_생성시_RESERVED_상태다() {
        Reservation r = Reservation.reserve(1L);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getOrderId()).isEqualTo(1L);
    }

    @Test
    void ship_상태가_SHIPPED로_전이된다() {
        Reservation r = Reservation.reserve(1L);
        r.ship();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void release_상태가_RELEASED로_전이된다() {
        Reservation r = Reservation.reserve(1L);
        r.release();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }
}
