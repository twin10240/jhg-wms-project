package com.jhg.wms.config;

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
     * 분류 전용 작은 풀. 큐가 차면 그냥 버린다 —
     * CallerRuns로 되돌리면 막으려던 것(요청 스레드가 분류를 기다림)이 그대로 일어나고,
     * 무제한 큐로 두면 밀린 분류가 메모리로 쌓인다. 분류는 빠져도 업무가 막히지 않으므로 버리는 쪽이 맞다.
     */
    @Bean(name = "classificationExecutor", destroyMethod = "shutdown")
    public ExecutorService classificationExecutor() {
        return new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                runnable -> {
                    Thread t = new Thread(runnable, "rma-classify");
                    t.setDaemon(true);
                    return t;
                },
                (runnable, executor) ->
                        log.warn("반품 사유 분류 대기열 포화 — 이번 건은 분류하지 않습니다."));
    }
}
