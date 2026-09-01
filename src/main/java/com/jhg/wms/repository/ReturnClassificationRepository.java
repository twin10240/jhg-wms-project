package com.jhg.wms.repository;

import com.jhg.wms.domain.ReturnClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReturnClassificationRepository extends JpaRepository<ReturnClassification, Long> {

    Optional<ReturnClassification> findByRmaReturnId(Long rmaReturnId);

    boolean existsByRmaReturnId(Long rmaReturnId);

    // 코호트 반품들의 분류를 한 번에 읽는다(반품마다 조회하면 N+1이 된다).
    List<ReturnClassification> findByRmaReturnIdIn(Collection<Long> rmaReturnIds);
}
