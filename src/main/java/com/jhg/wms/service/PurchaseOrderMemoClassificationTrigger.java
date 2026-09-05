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
public class PurchaseOrderMemoClassificationTrigger {

    private final PurchaseOrderMemoClassificationService service;
    private final Executor executor;

    public PurchaseOrderMemoClassificationTrigger(PurchaseOrderMemoClassificationService service,
                                                  @Qualifier("classificationExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    /**
     * 발주 커밋 후, 요청 스레드 밖에서 분류한다.
     *
     * 반품 쪽({@link ReturnClassificationTrigger})과 형태는 같지만 <b>이유가 다르다.</b>
     * 거기서 요청 밖으로 내보낸 것은 OMS의 POST /api/returns가 분류 지연만큼 늦어져
     * read-timeout에 걸리면 접수가 실패로 관측되기 때문이었다. 발주 생성은 관리자 폼 POST라
     * 그 제약이 없다 — 여기서 요청 밖으로 내보내는 이유는 사람을 기다리게 하지 않기 위해서다
     * (호출은 최대 40초: timeout 20s × maxRetries 1).
     *
     * 커밋 후여야 하는 이유는 하나 더 있다. 분류 대상 판별이 보충 요청과의 연결 여부를 보는데,
     * OMS 승인 경로에서는 그 링크가 발주 생성과 같은 트랜잭션 안에서 나중에 걸린다 —
     * 커밋 전에 판별하면 OMS 발주를 수동 발주로 잘못 읽는다.
     */
    public void classifyAfterCommit(Long purchaseOrderId, String memo) {
        if (memo == null || memo.isBlank()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 불리면 등록할 곳이 없다. 조용히 흘리면 "왜 분류가 안 되지"가 된다.
            log.warn("트랜잭션 밖에서 호출돼 메모 분류를 등록하지 못했습니다: poId={}", purchaseOrderId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // 대기열(50)이 가득 차면 기본 AbortPolicy가 RejectedExecutionException을 던진다.
                    // 여기서 잡지 않으면 afterCommit 밖으로 나가 스프링 커밋 완료 루프로 들어간다 —
                    // 분류 하나 못 돌린 것 때문에 커밋 후처리 자체가 흔들려선 안 된다.
                    executor.execute(() -> {
                        try {
                            service.classifyAndSave(purchaseOrderId, memo);
                        } catch (Exception e) {
                            // classifyAndSave의 try/catch는 메서드 본문 안에서만 유효하다.
                            // 저장소 메서드의 커밋 시점 실패(유니크 제약 위반·커넥션 단절)는 그 catch를
                            // 비켜가 이 Runnable 밖으로 던져지고, 이 executor에는 UncaughtExceptionHandler가
                            // 없어 slf4j 로그에 전혀 남지 않는다. 반드시 여기서 잡는다.
                            log.warn("발주 메모 분류 실패(무시): poId={}", purchaseOrderId, e);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    log.warn("발주 메모 분류 대기열 포화(무시): poId={} — 이번 건은 분류하지 않습니다.",
                            purchaseOrderId);
                }
            }
        });
    }
}
