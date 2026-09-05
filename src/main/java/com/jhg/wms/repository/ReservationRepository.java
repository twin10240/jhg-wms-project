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
     * <p>qtyByProductId는 지연로딩이라 상품별 집계에서 터진다 — 여기서 즉시 페치한다.
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
