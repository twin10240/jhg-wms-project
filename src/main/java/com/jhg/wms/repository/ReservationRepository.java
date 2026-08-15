package com.jhg.wms.repository;

import com.jhg.wms.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.orderId = :orderId")
    Optional<Reservation> findByOrderIdWithLock(Long orderId);

    // qtyByProductId(@ElementCollection)는 지연로딩이라 open-in-view:false 뷰에서 접근 시 예외.
    // 관리자 예약 화면이 상품·수량을 보여주므로 EntityGraph로 즉시 페치한다.
    @EntityGraph(attributePaths = "qtyByProductId")
    List<Reservation> findAllByOrderByIdDesc();
}
