package com.jhg.wms.repository;

import com.jhg.wms.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
