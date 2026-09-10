package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrderBriefing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PurchaseOrderBriefingRepository extends JpaRepository<PurchaseOrderBriefing, Long> {

    /** 화면은 마지막 것 하나만 보여준다. 이력은 평가와 사후 확인용으로 남는다. */
    Optional<PurchaseOrderBriefing> findFirstByOrderByCreatedAtDesc();
}
