package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/** orderId당 예약 원장. unique orderId로 멱등성을 보장한다. 예약 수량을 함께 저장해 재고 SSOT가 된다. */
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

    /** 예약된 상품별 수량. ship/release는 호출자 요청이 아니라 이 원장을 재생한다(SSOT). */
    @ElementCollection
    @CollectionTable(name = "reservation_item", joinColumns = @JoinColumn(name = "reservation_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "qty", nullable = false)
    private Map<Long, Integer> qtyByProductId = new HashMap<>();

    public static Reservation reserve(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation r = new Reservation();
        r.orderId = orderId;
        r.status = ReservationStatus.RESERVED;
        r.qtyByProductId = new HashMap<>(qtyByProductId);
        return r;
    }

    public void ship()    { this.status = ReservationStatus.SHIPPED; }
    public void release() { this.status = ReservationStatus.RELEASED; }
}
