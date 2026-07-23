package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("select distinct po from PurchaseOrder po left join fetch po.items order by po.id desc")
    List<PurchaseOrder> findAllWithItems();

    @Query("select distinct po from PurchaseOrder po left join fetch po.items where po.id = :id")
    Optional<PurchaseOrder> findWithItemsById(@Param("id") Long id);
}
