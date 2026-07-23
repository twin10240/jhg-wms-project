package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * receivedQty 도입 전 배포분 백필 전용 쿼리.
 * 네이티브 SQL을 쓰는 이유: receivedQty는 primitive int로 매핑돼 있어 JPQL의 `is null` 취급이
 * 구현체마다 다르고, 이 두 문장은 엔티티 로직이 아니라 스키마 마이그레이션이다.
 * clearAutomatically: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로, 안 비우면 후속 조회가 stale을 본다.
 */
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update purchase_order_item set received_qty = quantity
             where received_qty is null
               and purchase_order_id in (select purchase_order_id from purchase_order where status = 'RECEIVED')
            """, nativeQuery = true)
    int backfillReceivedQtyForReceivedOrders();

    @Modifying(clearAutomatically = true)
    @Query(value = "update purchase_order_item set received_qty = 0 where received_qty is null",
           nativeQuery = true)
    int backfillRemainingReceivedQty();
}
