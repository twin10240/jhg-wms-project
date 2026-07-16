package com.jhg.wms.repository;

import com.jhg.wms.domain.ReplenishmentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplenishmentRequestRepository extends JpaRepository<ReplenishmentRequest, Long> {

    @Query("select distinct r from ReplenishmentRequest r left join fetch r.items where r.requestKey = :requestKey")
    Optional<ReplenishmentRequest> findByRequestKeyWithItems(@Param("requestKey") UUID requestKey);

    @Query("select distinct r from ReplenishmentRequest r left join fetch r.items order by r.id desc")
    List<ReplenishmentRequest> findAllWithItems();

    Optional<ReplenishmentRequest> findByPurchaseOrderId(Long purchaseOrderId);
}
