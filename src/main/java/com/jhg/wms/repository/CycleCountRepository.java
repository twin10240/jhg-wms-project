package com.jhg.wms.repository;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CycleCountRepository extends JpaRepository<CycleCount, Long> {

    @EntityGraph(attributePaths = "items")
    @Override
    Optional<CycleCount> findById(Long id);

    @EntityGraph(attributePaths = "items")
    List<CycleCount> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = "items")
    List<CycleCount> findByStatusOrderByIdDesc(CycleCountStatus status);

    long countByStatus(CycleCountStatus status);

    /** 아직 종결되지 않은 세션이 잡고 있는 상품들. 겹침 검사 한 번에 쓰려고 productId만 뽑는다. */
    @Query("SELECT DISTINCT i.productId FROM CycleCount c JOIN c.items i " +
           "WHERE c.status IN (com.jhg.wms.domain.CycleCountStatus.OPEN, " +
           "                   com.jhg.wms.domain.CycleCountStatus.SUBMITTED)")
    List<Long> findOpenProductIds();
}
