package com.jhg.wms.repository;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
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

    /** 이 상품이 종결되지 않은 세션에 잡혀 있는가. 조정 차단은 상품 하나만 보면 되므로 목록 대신 exists. */
    @Query("SELECT COUNT(i) > 0 FROM CycleCount c JOIN c.items i WHERE i.productId = :productId " +
           "AND c.status IN (com.jhg.wms.domain.CycleCountStatus.OPEN, " +
           "                 com.jhg.wms.domain.CycleCountStatus.SUBMITTED)")
    boolean existsOpenByProductId(Long productId);

    /**
     * 시작 시각이 구간에 든 세션. 분석이 쓰는 유일한 조회다.
     * <p>승인 시각이 아니라 <b>시작 시각</b> 기준인 이유: 반려·진행 중 세션은 승인 시각이 없어
     * 그걸 기준으로 삼으면 상태 분포를 낼 수 없다. 끝은 열린 구간이라 호출자가 하루를 더해 넘긴다.
     */
    @EntityGraph(attributePaths = "items")
    @Query("SELECT c FROM CycleCount c WHERE c.createdAt >= :fromAt AND c.createdAt < :toAtExclusive " +
           "ORDER BY c.id")
    List<CycleCount> findCreatedBetween(LocalDateTime fromAt, LocalDateTime toAtExclusive);

    /** 아직 종결되지 않은 세션이 잡고 있는 상품들. 겹침 검사 한 번에 쓰려고 productId만 뽑는다. */
    @Query("SELECT DISTINCT i.productId FROM CycleCount c JOIN c.items i " +
           "WHERE c.status IN (com.jhg.wms.domain.CycleCountStatus.OPEN, " +
           "                   com.jhg.wms.domain.CycleCountStatus.SUBMITTED)")
    List<Long> findOpenProductIds();
}
