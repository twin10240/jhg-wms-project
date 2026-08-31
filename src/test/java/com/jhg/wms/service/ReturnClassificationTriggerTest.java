package com.jhg.wms.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ReturnClassificationTriggerTest {

    ReturnClassificationService service;
    List<Runnable> submitted;
    ReturnClassificationTrigger trigger;

    @BeforeEach
    void setUp() {
        service = mock(ReturnClassificationService.class);
        submitted = new ArrayList<>();
        // executor에 실제로 넘어갔는지 보려고 실행을 가로챈다 — 요청 스레드에서
        // 바로 돌면 OMS 응답이 분류 지연에 묶이므로, 그 위임 자체가 검증 대상이다.
        trigger = new ReturnClassificationTrigger(service, submitted::add);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    private void fireAfterCommit() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                .forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void 커밋_전에는_분류하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, "깨졌어요");

        assertThat(submitted).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void 커밋_후에_executor로_넘긴다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, "깨졌어요");
        fireAfterCommit();

        assertThat(submitted).hasSize(1);
        verifyNoInteractions(service);   // 아직 executor가 실행하지 않았다

        submitted.get(0).run();
        verify(service).classifyAndSave(7L, "깨졌어요");
    }

    @Test
    void 사유가_비면_동기화_등록_자체를_하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, null);
        trigger.classifyAfterCommit(8L, "  ");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void 커밋_시점_실패가_풀_스레드_밖으로_전파되지_않는다() {
        // ReturnClassificationService.classifyAndSave의 try/catch는 메서드 본문 안에서만
        // 유효하고, @GeneratedValue(AUTO)라 실제 INSERT는 트랜잭션 커밋 시점에 플러시된다.
        // 그 커밋 시점 실패(유니크 위반, 커넥션 단절 등)를 흉내 내려고 스텁이 예외를 던지게 한다 —
        // 이걸 트리거가 잡지 못하면 executor 스레드 밖으로 던져져 slf4j를 거치지 않고 stderr로 샌다.
        doThrow(new RuntimeException("커밋 시점 실패")).when(service).classifyAndSave(7L, "깨졌어요");

        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, "깨졌어요");
        fireAfterCommit();

        assertThat(submitted).hasSize(1);
        assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
    }

    @Test
    void 대기열_포화로_거부돼도_afterCommit은_예외를_던지지_않는다() {
        // ClassificationExecutorConfig는 기본 AbortPolicy를 써서 대기열이 차면
        // RejectedExecutionException을 던진다. 그 예외가 afterCommit 밖으로 새면 스프링의
        // 커밋 완료 루프로 들어가버리므로, 트리거가 반드시 여기서 잡아 삼켜야 한다.
        Executor rejectingExecutor = runnable -> {
            throw new RejectedExecutionException("대기열 포화");
        };
        ReturnClassificationTrigger rejectingTrigger =
                new ReturnClassificationTrigger(service, rejectingExecutor);

        TransactionSynchronizationManager.initSynchronization();
        rejectingTrigger.classifyAfterCommit(7L, "깨졌어요");

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
    }
}
