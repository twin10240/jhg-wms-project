package com.jhg.wms.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class ClassificationExecutorConfig {

    /**
     * 종료 대기 5초. 지키려는 것은 API 호출이 아니라 그 뒤의 INSERT다.
     * 호출 자체는 최대 40초(timeout 20s × maxRetries 1)까지 갈 수 있지만, 분류 하나를 잃는 것은
     * 이 기능의 설계상 허용된 손실이라 그만큼 배포를 붙잡지 않는다.
     */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /** @PreDestroy에서 종료를 직접 다루기 위해 참조를 들고 있는다. */
    private ExecutorService executor;

    /**
     * 분류 전용 작은 풀. 반품 사유와 발주 메모가 같이 쓴다 — 둘 다 참고 정보라
     * 서로 밀려도 잃는 것이 같고, 풀을 따로 두면 놀고 있는 스레드만 늘어난다.
     * 큐가 차면 그냥 버린다 —
     * CallerRuns로 되돌리면 막으려던 것(요청 스레드가 분류를 기다림)이 그대로 일어나고,
     * 무제한 큐로 두면 밀린 분류가 메모리로 쌓인다. 분류는 빠져도 업무가 막히지 않으므로 버리는 쪽이 맞다.
     *
     * 거부 핸들러를 따로 두지 않고 기본 AbortPolicy를 쓴다 — 여기서는 어떤 요청이 버려졌는지
     * 알 방법이 없다. 그 id는 호출자인 각 트리거에만 있으므로, 포화 로그는 예외를
     * 던져 거기서 잡아 남기게 한다.
     *
     * destroyMethod를 비워 스프링의 추론 종료(Java 19+에서 ExecutorService는 AutoCloseable이라
     * close()가 무기한 대기한다)를 끄고, 아래 @PreDestroy에서 시간을 정해 직접 기다린다.
     */
    @Bean(name = "classificationExecutor", destroyMethod = "")
    public ExecutorService classificationExecutor() {
        this.executor = new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                runnable -> {
                    Thread t = new Thread(runnable, "classify");
                    t.setDaemon(true);
                    return t;
                });
        return this.executor;
    }

    /**
     * 진행 중인 분류에 커밋할 시간을 준다.
     *
     * shutdown()만 부르면 실행 중인 작업을 두고 즉시 반환한다. 이 풀의 스레드는 daemon이라
     * JVM이 그대로 내려가고, 분류기가 값을 돌려줬지만 아직 플러시되지 않은 행이 조용히 사라진다
     * (INSERT는 classifyAndSave가 반환된 뒤 트랜잭션 커밋 시점에 일어난다).
     * 잃어도 업무는 멀쩡하지만, 잃었다는 사실조차 남지 않는 것이 문제였다.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("분류 풀 종료 대기 {}초 초과 — 남은 작업을 중단합니다.", SHUTDOWN_WAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
