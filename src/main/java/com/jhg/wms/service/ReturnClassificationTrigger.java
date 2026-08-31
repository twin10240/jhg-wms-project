package com.jhg.wms.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
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
                try {
                    // 대기열(50)이 가득 차면 기본 AbortPolicy가 RejectedExecutionException을 던진다.
                    // 여기서 잡지 않으면 afterCommit 밖으로 나가 스프링 커밋 완료 루프로 들어간다 —
                    // 분류 하나 못 돌린 것 때문에 커밋 후처리 자체가 흔들려선 안 된다.
                    // rmaReturnId가 살아있는 유일한 지점이라, 몇 번이 버려졌는지는 여기서만 남길 수 있다.
                    executor.execute(() -> {
                        try {
                            service.classifyAndSave(rmaReturnId, reason);
                        } catch (Exception e) {
                            // ReturnClassificationService.classifyAndSave의 try/catch는 메서드
                            // 본문 안에서만 유효하다. @Transactional 프록시의 커밋(= INSERT 플러시)은
                            // 메서드가 반환된 뒤 일어나므로, 유니크 제약 위반이나 커넥션 단절 같은
                            // 커밋 시점 실패는 그 catch를 비켜가 여기 이 Runnable 밖으로 던져진다.
                            // 이 executor는 afterExecute도 UncaughtExceptionHandler도 없어 그대로
                            // 두면 JVM 기본 핸들러가 stderr에 찍고 끝나 slf4j 로그 인벤토리에 전혀
                            // 남지 않는다. 문서화된 문구를 그대로 재사용해 이 지점에서 반드시 잡는다.
                            log.warn("반품 사유 분류 실패(무시): rmaId={}", rmaReturnId, e);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    log.warn("반품 사유 분류 대기열 포화(무시): rmaId={} — 이번 건은 분류하지 않습니다.",
                            rmaReturnId);
                }
            }
        });
    }
}
