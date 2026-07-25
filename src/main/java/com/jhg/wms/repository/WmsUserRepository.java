package com.jhg.wms.repository;

import com.jhg.wms.domain.WmsUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WmsUserRepository extends JpaRepository<WmsUser, Long> {
    Optional<WmsUser> findByUsername(String username);
}
