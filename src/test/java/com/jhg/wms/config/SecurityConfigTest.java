package com.jhg.wms.config;

import com.jhg.wms.InitDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SecurityConfigTest {

    @Autowired List<SecurityFilterChain> chains;
    // 실제 Postgres(wms_test) 공유 DB에 InitDb가 재고를 실제로 커밋하는 것을 막는다 —
    // 이 테스트는 보안 체인 구성만 보므로 시딩과 무관하다(JhgWmsApplicationTests와 동일 사유).
    @MockitoBean InitDb initDb;

    @Test
    void 보안_체인이_두_개로_분리된다() {
        assertThat(chains).hasSize(2);   // apiChain + webChain
    }
}
