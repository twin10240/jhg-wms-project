package com.jhg.wms.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
}
