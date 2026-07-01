package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** orderId당 예약 원장. unique orderId로 멱등성을 보장한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id @GeneratedValue
    @Column(name = "reservation_id")
    private Long id;

    @Column(unique = true, nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    public static Reservation reserve(Long orderId) {
        Reservation r = new Reservation();
        r.orderId = orderId;
        r.status = ReservationStatus.RESERVED;
        return r;
    }

    public void ship()    { this.status = ReservationStatus.SHIPPED; }
    public void release() { this.status = ReservationStatus.RELEASED; }
}
