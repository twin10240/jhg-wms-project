package com.jhg.wms.repository;

import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    List<InventoryTransaction> findAllByOrderByIdDesc();

    // 관리자 화면용 — 원장이 계속 자라므로(입고·출고 라인마다 1행) 전건이 아닌 최신 200건만.
    List<InventoryTransaction> findTop200ByOrderByIdDesc();
    List<InventoryTransaction> findTop200ByTypeOrderByIdDesc(InventoryTransactionType type);

    // 기존 배포분 백필: type이 없는 구 조정행을 ADJUST로 채운다.
    // clearAutomatically: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않아, 이미 로드된 관리 엔티티는 이후 조회에서도
    // stale(type=null)로 보인다 — 컨텍스트를 비워 후속 조회가 DB를 다시 읽게 한다.
    @Modifying(clearAutomatically = true)
    @Query("update InventoryTransaction t set t.type = com.jhg.wms.domain.InventoryTransactionType.ADJUST where t.type is null")
    int assignAdjustTypeToLegacy();

    boolean existsByProductIdAndType(Long productId, InventoryTransactionType type);

    // OPENING 소급 시 이미 쌓인 원장 델타합을 구해 잔여분만 OPENING으로 채운다(불변식 Σdelta==onHand 유지).
    @Query("select coalesce(sum(t.delta), 0) from InventoryTransaction t where t.productId = :productId")
    int sumDeltaByProductId(@Param("productId") Long productId);
}
