package com.jhg.wms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ClassificationExecutorConfig {

    /**
     * 분류 전용 작은 풀. 큐가 차면 그냥 버린다 —
     * CallerRuns로 되돌리면 막으려던 것(요청 스레드가 분류를 기다림)이 그대로 일어나고,
     * 무제한 큐로 두면 밀린 분류가 메모리로 쌓인다. 분류는 빠져도 업무가 막히지 않으므로 버리는 쪽이 맞다.
     *
     * 거부 핸들러를 따로 두지 않고 기본 AbortPolicy를 쓴다 — 여기서는 어떤 요청이 버려졌는지(rmaReturnId)
     * 알 방법이 없다. 그 id는 호출자인 ReturnClassificationTrigger에만 있으므로, 포화 로그는 예외를
     * 던져 거기서 잡아 남기게 한다.
     */
    @Bean(name = "classificationExecutor", destroyMethod = "shutdown")
    public ExecutorService classificationExecutor() {
        return new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                runnable -> {
                    Thread t = new Thread(runnable, "rma-classify");
                    t.setDaemon(true);
                    return t;
                });
    }
}
