package com.jhg.wms.repository;

import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RmaReturnRepository extends JpaRepository<RmaReturn, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<RmaReturn> findByRequestKey(String requestKey);

    @EntityGraph(attributePaths = "items")
    List<RmaReturn> findByOrderIdAndStatusNot(Long orderId, RmaStatus status);

    @EntityGraph(attributePaths = "items")
    List<RmaReturn> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = "items")
    List<RmaReturn> findByStatusOrderByIdDesc(RmaStatus status);

    @EntityGraph(attributePaths = "items")
    @Override
    Optional<RmaReturn> findById(Long id);
}
