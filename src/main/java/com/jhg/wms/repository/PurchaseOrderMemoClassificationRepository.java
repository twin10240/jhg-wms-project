package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrderMemoClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PurchaseOrderMemoClassificationRepository
        extends JpaRepository<PurchaseOrderMemoClassification, Long> {

    Optional<PurchaseOrderMemoClassification> findByPurchaseOrderId(Long purchaseOrderId);

    boolean existsByPurchaseOrderId(Long purchaseOrderId);
}
