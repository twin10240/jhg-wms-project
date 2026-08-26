package com.jhg.wms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JhgWmsApplicationTests {

	@Autowired ServerProperties serverProperties;
	// 실제 Postgres(wms_test)를 쓰는 이후 @DataJpaTest들과 물리 DB를 공유한다 — InitDb의
	// @PostConstruct 시딩(product_id 1~20)이 실제로 커밋되면 그 테스트들의 고정 productId와 충돌한다.
	// 이 클래스는 시딩 내용을 검증하지 않으므로 목으로 대체해 커밋 자체를 막는다.
	@MockitoBean InitDb initDb;

	@Test
	void contextLoads() {
	}

	@Test
	void 세션_쿠키는_WMS_전용_이름을_사용한다() {
		assertThat(serverProperties.getServlet().getSession().getCookie().getName())
				.isEqualTo("WMSSESSION");
	}

}
