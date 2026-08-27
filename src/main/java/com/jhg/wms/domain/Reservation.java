package com.jhg.wms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    @JdbcTypeCode(SqlTypes.VARCHAR)   // DB 네이티브 ENUM 대신 VARCHAR 저장 — 값 추가 시 기존 컬럼이 거부하는 사고 방지(PostgreSQL도 동일)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    /** 예약된 상품별 수량. ship/release는 호출자 요청이 아니라 이 원장을 재생한다(SSOT). */
    @ElementCollection
    @CollectionTable(name = "reservation_item", joinColumns = @JoinColumn(name = "reservation_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "qty", nullable = false)
    private Map<Long, Integer> qtyByProductId = new HashMap<>();

    /** 데모용 송장. 실제 택배사 연동이 아니라 MOCK 하나만 지원한다. */
    public static final String CARRIER_CODE = "MOCK";
    public static final String CARRIER_NAME = "테스트택배";

    // 송장번호의 시각은 issuedAt과 같은 UTC다. 로컬 시각으로 만들면 응답 안에서 두 값이
    // 서로 다른 숫자를 보여준다(KST 15:30 vs UTC 06:30Z).
    private static final DateTimeFormatter TRACKING_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * 송장번호. 주문당 1회만 발급되므로 unique. 출고 전·해제된 예약은 null이 정상이다.
     * 이 unique 제약은 최후 방어선일 뿐 "주문당 1건"을 실제로 강제하는 것은 shipAll의
     * trackingNumber == null 가드(row lock 하)다 — 그 가드가 빠지면 재발급마다 초가 달라져
     * 다른 문자열이 나오므로 이 인덱스를 그냥 통과한다.
     */
    @Column(unique = true)
    private String trackingNumber;

    @Column
    private String carrierCode;

    /** 서버 시간대에 따라 해석이 갈리면 안 되는 값이라 Instant다(서비스 경계를 넘는다). */
    @Column
    private Instant issuedAt;

    public static Reservation reserve(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation r = new Reservation();
        r.orderId = orderId;
        r.status = ReservationStatus.RESERVED;
        r.qtyByProductId = new HashMap<>(qtyByProductId);
        return r;
    }

    public void ship()    { this.status = ReservationStatus.SHIPPED; }
    public void release() { this.status = ReservationStatus.RELEASED; }

    /**
     * 출고 송장 발급. 주문당 1회이며 재발급하지 않는다 — 호출 측이 trackingNumber == null을 확인하고 부른다.
     * 여기서 다시 검사하지 않는 이유: 발급 여부 판단은 락을 쥔 서비스의 책임이고,
     * 도메인이 조용히 no-op 하면 호출 측 버그가 숨는다.
     */
    public void issueShipment(Instant now) {
        this.carrierCode = CARRIER_CODE;
        // issued_at 컬럼은 timestamp(6) — 마이크로초까지만 저장된다. 여기서 미리 자르지 않으면
        // Linux(나노초 정밀도 Instant.now())에서는 메모리 값과 재조회 값이 달라진다: 저장 직후
        // 응답에 실리는 값은 나노초가 살아있지만, DB는 그걸 못 담아 조용히 잘라버리고 이후 조회는
        // 잘린 값을 돌려준다. 마이크로초로 미리 자르면 두 값이 항상 같아 플랫폼에 관계없이 일관된다.
        this.issuedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.trackingNumber = CARRIER_CODE + "-" + orderId + "-" + TRACKING_TS.format(now);
    }
}
