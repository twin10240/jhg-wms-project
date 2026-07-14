package com.jhg.wms.repository;

import com.jhg.wms.domain.InventoryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long> {
    List<InventoryAdjustment> findAllByOrderByIdDesc();
}
