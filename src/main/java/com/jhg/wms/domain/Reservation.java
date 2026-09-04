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
import java.util.UUID;

/**
 * 주문당 예약 원장. <b>멱등의 기준은 {@code requestKey}(OMS가 만든 UUID)이지 {@code orderId}가 아니다.</b>
 * 예약 수량을 함께 저장해 재고 SSOT가 된다.
 *
 * <p>orderId는 OMS DB의 시퀀스라 전역 유일하지 않다 — OMS DB를 새로 만들면 1부터 다시 발급되고,
 * 그때 WMS에 남은 옛 예약과 신규 주문이 같은 번호를 갖는다. 그래서 orderId는 화면·로그·수불대장의
 * 상관관계 표시용으로만 남기고 유니크 제약을 두지 않는다(같은 규칙: {@code RmaResponse}의 rmaId 주석).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id @GeneratedValue
    @Column(name = "reservation_id")
    private Long id;

    /** OMS가 주문 생성 시 발급하는 연동 식별자. 예약 멱등·조회의 유일한 키다. */
    @Column(nullable = false, unique = true, columnDefinition = "uuid")
    private UUID requestKey;

    /** OMS 주문 번호. 표시·추적용이며 유일하지 않다 — 키로 쓰지 말 것. */
    @Column(nullable = false)
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

    /**
     * 배송 완료 시각. null이면 아직 배송 중이다.
     * <p>상태를 ReservationStatus에 값으로 넣지 않은 이유: SHIPPED 비교가 세 군데 있고
     * (shipAll의 재차감 방지, releaseAll의 해제 거부, RmaService의 반품 게이트) 값을 추가하면
     * 셋 다 의미가 조용히 뒤집힌다. 배송 완료는 출고의 후속 사실이지 별개 상태가 아니다.
     */
    @Column
    private Instant deliveredAt;

    /**
     * 예약 생성 시각. 이 필드가 생기기 전 행은 null이다 — 알 수 없는 정보라 백필하지 않는다
     * ({@code InventoryTransaction.actor}와 같은 규칙).
     *
     * <p>예약이 얼마나 오래 재고를 붙들고 있었는지는 이 값 없이는 잴 수 없다. 예약 생성은
     * onHand를 바꾸지 않아 원장에 행을 남기지 않으므로, 원장으로 우회할 수도 없다.
     *
     * <p>{@code issueShipment}·{@code deliver}와 달리 시각을 인자로 받지 않는다. 그쪽은
     * 송장번호 문자열과 issuedAt이 <b>같은 순간</b>이어야 해서 호출자가 넘기는 것이고,
     * 여기엔 그런 결합이 없다({@code InventoryTransaction.of}와 같다).
     */
    @Column
    private Instant createdAt;

    /**
     * 예약 해제 시각. 해제되지 않았으면 null이다. 이 필드가 생기기 전 행도 null이다.
     *
     * <p>출고 시각에 해당하는 필드는 따로 두지 않았다 — {@code shipAll}이 {@code ship()} 직후
     * 송장을 발급하므로 {@code issuedAt}이 곧 출고 시각이다. 같은 사실을 두 컬럼에 적으면
     * 언젠가 둘이 어긋난다.
     */
    @Column
    private Instant releasedAt;

    public static Reservation reserve(UUID requestKey, Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation r = new Reservation();
        r.requestKey = requestKey;
        r.orderId = orderId;
        r.status = ReservationStatus.RESERVED;
        r.qtyByProductId = new HashMap<>(qtyByProductId);
        // 절삭 이유는 issueShipment와 같다 — 컬럼이 timestamp(6)라 자르지 않으면
        // 나노초 정밀도 플랫폼에서 저장 직후 값과 재조회 값이 달라진다.
        r.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return r;
    }

    public void ship()    { this.status = ReservationStatus.SHIPPED; }

    public void release() {
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

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
        // requestKey 앞 8자를 붙이는 이유: 나머지 부분(orderId + 초 단위 시각)은 OMS DB가 초기화돼
        // orderId가 재사용되면 같은 초에 같은 문자열을 만들 수 있고, 그러면 unique 제약에 걸려
        // 신규 주문의 출고가 통째로 실패한다. 예약마다 유일한 값은 requestKey뿐이다.
        this.trackingNumber = CARRIER_CODE + "-" + orderId + "-" + TRACKING_TS.format(now)
                + "-" + requestKey.toString().substring(0, 8);
    }

    /**
     * 배송 완료 기록. 재고는 출고에서 이미 차감됐으므로 원장을 만들지 않는다 — 사실만 남긴다.
     * 절삭 이유는 issueShipment와 같다(issued_at/delivered_at 모두 timestamp(6)).
     * 재호출 방지는 호출 측(락을 쥔 서비스)의 책임 — issueShipment와 같은 규칙이다.
     */
    public void deliver(Instant now) {
        this.deliveredAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
