package com.jhg.wms.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분류 호출이 트랜잭션 밖에서 일어나는지 본다.
 *
 * 다른 분류 테스트들은 서비스를 직접 new 해서 쓴다(프록시가 없어 @Transactional이 무의미하다).
 * 트랜잭션 경계는 프록시가 만드는 것이라, 그 경계를 재려면 컨테이너가 만든 빈이어야 한다.
 *
 * 재는 것이 왜 중요한가: 이 메서드가 트랜잭션 안에서 돌면 첫 DB 접근에서 잡은 커넥션을
 * HTTP 호출이 끝날 때까지(최대 40초) 붙잡는다. 눈에 보이는 증상이 없어 회귀해도 조용하다.
 */
@SpringBootTest
@Import(ReturnClassificationTransactionBoundaryTest.StubClassifierConfig.class)
class ReturnClassificationTransactionBoundaryTest {

    static final AtomicBoolean 분류_호출_중_트랜잭션_활성 = new AtomicBoolean(true);

    @TestConfiguration
    static class StubClassifierConfig {
        /** 실제 API 대신, 불린 순간의 트랜잭션 상태만 기록하고 empty를 낸다. */
        @Bean
        @Primary
        ReturnReasonClassifier 트랜잭션을_기록하는_분류기() {
            return reason -> {
                분류_호출_중_트랜잭션_활성.set(TransactionSynchronizationManager.isActualTransactionActive());
                return Optional.empty();
            };
        }
    }

    @Autowired
    ReturnClassificationService service;

    @Test
    void 분류를_호출하는_동안에는_트랜잭션이_열려_있지_않다() {
        service.classifyAndSave(999_001L, "받았는데 모서리가 깨져 있어요");

        assertThat(분류_호출_중_트랜잭션_활성).isFalse();
    }
}
