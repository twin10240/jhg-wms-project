package com.jhg.wms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SecurityConfigTest {

    @Autowired List<SecurityFilterChain> chains;

    @Test
    void 보안_체인이_두_개로_분리된다() {
        assertThat(chains).hasSize(2);   // apiChain + webChain
    }
}
