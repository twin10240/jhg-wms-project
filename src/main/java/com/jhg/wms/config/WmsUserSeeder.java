package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WmsUserSeeder {

    private final WmsUserRepository userRepository;
    private final PasswordEncoder encoder;
    private final String operatorUser;
    private final String operatorPassword;
    private final String managerUser;
    private final String managerPassword;

    public WmsUserSeeder(WmsUserRepository userRepository, PasswordEncoder encoder,
                         @Value("${wms.seed-users.operator-user}") String operatorUser,
                         @Value("${wms.seed-users.operator-password}") String operatorPassword,
                         @Value("${wms.seed-users.manager-user}") String managerUser,
                         @Value("${wms.seed-users.manager-password}") String managerPassword) {
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.operatorUser = operatorUser;
        this.operatorPassword = operatorPassword;
        this.managerUser = managerUser;
        this.managerPassword = managerPassword;
    }

    @PostConstruct
    @Transactional
    public void seed() {
        upsert(operatorUser, operatorPassword, WmsRole.OPERATOR);
        upsert(managerUser, managerPassword, WmsRole.MANAGER);
    }

    private void upsert(String username, String rawPassword, WmsRole role) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank())
            throw new IllegalStateException("wms.seed-users 자격증명이 비어 있습니다: role=" + role);
        if (userRepository.findByUsername(username).isPresent())
            return;
        userRepository.save(WmsUser.create(username, encoder.encode(rawPassword), role));
    }
}
