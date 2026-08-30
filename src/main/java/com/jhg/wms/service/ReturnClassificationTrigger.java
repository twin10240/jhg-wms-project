package com.jhg.wms.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

@Component
public class ReturnClassificationTrigger {

    private final ReturnClassificationService service;
    private final Executor executor;

    public ReturnClassificationTrigger(ReturnClassificationService service,
                                       @Qualifier("classificationExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    /**
     * 접수 커밋 후, 요청 스레드 밖에서 분류한다.
     *
     * afterCommit만으로는 부족하다 — 커밋 뒤에 돌긴 하지만 여전히 요청 스레드라,
     * 거기서 LLM을 부르면 OMS의 POST /api/returns 응답이 분류 지연만큼 늦어지고
     * OMS read-timeout에 걸리면 접수가 실패로 관측된다. 접수는 이미 커밋됐으니
     * 분류를 요청 밖으로 내보내도 잃는 것이 없다.
     */
    public void classifyAfterCommit(Long rmaReturnId, String reason) {
        if (reason == null || reason.isBlank()) return;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> service.classifyAndSave(rmaReturnId, reason));
            }
        });
    }
}
