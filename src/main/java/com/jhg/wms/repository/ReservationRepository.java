package com.jhg.wms.repository;

import com.jhg.wms.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByRequestKey(UUID requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.requestKey = :requestKey")
    Optional<Reservation> findByRequestKeyWithLock(UUID requestKey);

    /**
     * orderId로 찾는 레거시 상관관계 경로 — <b>Optional이 아니라 목록이다.</b>
     * orderId는 더 이상 유일하지 않다(OMS DB 초기화로 재사용된다). 최신 예약이 먼저 온다.
     *
     * <p>남아 있는 사용처는 RMA 접수 게이트 하나뿐이고, 거기서 최신 예약을 고른다.
     * OMS의 반품 요청이 주문의 requestKey를 함께 싣게 되면 이 메서드는 삭제 대상이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.orderId = :orderId ORDER BY r.id DESC")
    List<Reservation> findByOrderIdLatestFirstWithLock(Long orderId);

    // qtyByProductId(@ElementCollection)는 지연로딩이라 open-in-view:false 뷰에서 접근 시 예외.
    // 관리자 예약 화면이 상품·수량을 보여주므로 EntityGraph로 즉시 페치한다.
    @EntityGraph(attributePaths = "qtyByProductId")
    List<Reservation> findAllByOrderByIdDesc();

    /**
     * 종료 시각이 구간에 든 예약. 체류 분석이 쓰는 유일한 조회다.
     *
     * <p><b>생성 시각이 아니라 종료 시각 기준이다.</b> 생성 시각으로 자르면 구간 끝무렵에 생긴
     * 예약이 아직 안 끝나 빠지고, 나중에 같은 기간을 다시 부르면 그때는 끝나 있어서 숫자가 바뀐다.
     * 종료 시각으로 자르면 그 구간은 한 번 확정되면 변하지 않는다 — 보고서에 인용할 수 있는 쪽이다.
     *
     * <p>SHIPPED인데 issuedAt이 null인 행은 비교가 실패해 여기 안 들어온다. 별도 제외 카운터를
     * 두지 않은 이유: shipAll이 ship() 직후 항상 issueShipment를 부르고 송장 없는 기존 주문까지
     * 메우므로 앞으로 생기지 않는다.
     *
     * <p>다만 그 메움이 issuedAt을 <b>출고 시각이 아니라 메운 시각</b>으로 채운다는 뜻이기도 하다.
     * 오래전에 출고됐지만 송장이 없던 예약이 shipAll 재시도로 issuedAt=지금이 되면, 이 조회는
     * createdAt(오래전)부터 지금까지를 체류로 잡아 그 행을 잴 수 없는 게 아니라 <b>실제보다
     * 훨씬 길게 잘못 잰다</b> — 메워진 그날 구간의 최댓값·p90을 끌어올릴 수 있다.
     *
     * <p>qtyByProductId는 지연로딩이다. ReservationAnalyticsService는 클래스 레벨
     * {@code @Transactional(readOnly = true)}라 세션이 열려 있어 터지지는 않는다 — 대신
     * 상품별 집계에서 행마다 지연 쿼리가 나가는 N+1이 된다. 여기서 즉시 페치해 막는다.
     *
     * <p>행 수 상한은 없다. 366일 구간이면 그해 끝난 예약 전부와 qtyByProductId를 메모리에
     * 올린다 — 응답이 작아 토큰 비용은 없지만(원장의 500행 상한과 다른 이유) 네 분석 조회 중
     * 서버 메모리를 가장 많이 쓰는 것은 이쪽이다. 주문량이 늘면 여기가 먼저 아프다.
     * ponytail: 상한 없음, 트래픽이 늘면 원장처럼 최근 N행 자르기 + total 필드로 전환한다.
     */
    @EntityGraph(attributePaths = "qtyByProductId")
    @Query("""
           SELECT r FROM Reservation r
           WHERE (r.status = com.jhg.wms.domain.ReservationStatus.SHIPPED
                  AND r.issuedAt >= :fromAt AND r.issuedAt < :toAtExclusive)
              OR (r.status = com.jhg.wms.domain.ReservationStatus.RELEASED
                  AND r.releasedAt >= :fromAt AND r.releasedAt < :toAtExclusive)
           ORDER BY r.id
           """)
    List<Reservation> findEndedBetween(Instant fromAt, Instant toAtExclusive);

    /**
     * 그 시각에 아직 끝나지 않았던 예약 수. 체류 분포의 <b>생존 편향 크기</b>다.
     *
     * <p>오래 붙들려 있는 예약일수록 아직 안 끝나 분포에 안 잡힌다. 이 수를 모르고 중앙값만
     * 인용하면 실제보다 짧게 보인다.
     *
     * <p>createdAt이 null인 행은 세지 않는다 — 언제 시작했는지 모르면 그 시각에 열려 있었는지도 모른다.
     *
     * <p>일부러 createdAt 하한을 두지 않았다({@code from}과 무관하다). 묻는 것은 "그 순간에
     * 몇 건이 재고를 붙들고 있었나"이지 "그 구간 안에서 시작된 것 중 몇 건인가"가 아니다 —
     * 구간이 언제 시작했는지는 그 순간의 잔량과 관계가 없다.
     */
    @Query("""
           SELECT COUNT(r) FROM Reservation r
           WHERE r.createdAt IS NOT NULL AND r.createdAt < :at
             AND (r.status = com.jhg.wms.domain.ReservationStatus.RESERVED
                  OR (r.status = com.jhg.wms.domain.ReservationStatus.SHIPPED AND r.issuedAt >= :at)
                  OR (r.status = com.jhg.wms.domain.ReservationStatus.RELEASED AND r.releasedAt >= :at))
           """)
    long countOpenAt(Instant at);
}
