package com.jhg.wms.repository;

import com.jhg.wms.domain.ReturnClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReturnClassificationRepository extends JpaRepository<ReturnClassification, Long> {

    Optional<ReturnClassification> findByRmaReturnId(Long rmaReturnId);

    boolean existsByRmaReturnId(Long rmaReturnId);
}
