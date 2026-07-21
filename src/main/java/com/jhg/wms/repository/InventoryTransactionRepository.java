package com.jhg.wms.repository;

import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    List<InventoryTransaction> findAllByOrderByIdDesc();

    // 기존 배포분 백필: type이 없는 구 조정행을 ADJUST로 채운다.
    // clearAutomatically: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않아, 이미 로드된 관리 엔티티는 이후 조회에서도
    // stale(type=null)로 보인다 — 컨텍스트를 비워 후속 조회가 DB를 다시 읽게 한다.
    @Modifying(clearAutomatically = true)
    @Query("update InventoryTransaction t set t.type = com.jhg.wms.domain.InventoryTransactionType.ADJUST where t.type is null")
    int assignAdjustTypeToLegacy();

    boolean existsByProductIdAndType(Long productId, InventoryTransactionType type);
}
