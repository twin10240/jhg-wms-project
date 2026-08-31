package com.jhg.wms.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 대상은 "종료가 진행 중인 작업을 기다리는가"다.
 * shutdown()만 부르면 즉시 반환하고 daemon 스레드는 JVM과 함께 사라지므로,
 * 분류기가 값을 돌려줬지만 아직 커밋되지 않은 행이 흔적 없이 없어진다.
 */
class ClassificationExecutorConfigTest {

    @Test
    void 종료가_진행_중인_작업의_완료를_기다린다() throws Exception {
        ClassificationExecutorConfig config = new ClassificationExecutorConfig();
        ExecutorService executor = config.classificationExecutor();

        CountDownLatch 시작함 = new CountDownLatch(1);
        AtomicBoolean 끝까지_돌았다 = new AtomicBoolean(false);

        executor.execute(() -> {
            시작함.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            끝까지_돌았다.set(true);
        });
        // 작업이 실제로 실행에 들어간 뒤 종료를 건다 — 큐에만 있는 상태를 재는 것이 아니다.
        시작함.await();

        config.shutdown();

        assertThat(끝까지_돌았다).isTrue();
        assertThat(executor.isTerminated()).isTrue();
    }
}
