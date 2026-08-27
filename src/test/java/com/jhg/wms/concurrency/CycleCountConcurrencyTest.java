package com.jhg.wms.concurrency;

import com.jhg.wms.config.ActorProvider;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.service.CycleCountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("실사 세션 개설의 동시성")
@Import(CycleCountConcurrencyTest.ActorConfig.class)
class CycleCountConcurrencyTest extends ConcurrencySupport {

    static final AtomicReference<String> ACTOR = new AtomicReference<>("operator");

    @TestConfiguration
    static class ActorConfig {
        @Bean
        @Primary
        ActorProvider testActorProvider() {
            return ACTOR::get;
        }
    }

    @Autowired CycleCountService cycleCountService;
    @Autowired CycleCountRepository cycleCountRepository;

    @BeforeEach
    void 행위자를_초기화한다() {
        ACTOR.set("operator");
    }

    @AfterEach
    void 실사_세션을_정리한다() {
        cycleCountRepository.deleteAll();
    }

    @Test
    void 같은_상품으로_두_세션을_동시에_열면_하나만_성공한다() {
        long pid = PID_BASE + 100;
        seedInventory(pid, 50);

        RaceResult result = race(2, i -> {
            cycleCountService.open(List.of(pid), "동시 개설 " + i);
            return true;
        });

        assertThat(result.succeeded())
                .as("겹침 검사와 생성 사이에 락이 없어 두 세션이 같이 열렸다")
                .isEqualTo(1);
        assertThat(cycleCountRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_세션을_두_번_동시에_승인해도_한_번만_반영된다() {
        long pid = PID_BASE + 101;
        seedInventory(pid, 50);

        CycleCount session = tx.execute(s -> cycleCountService.open(List.of(pid), "동시 승인"));
        long sessionId = session.getId();
        long itemId = tx.execute(s -> cycleCountService.findById(sessionId).getItems().get(0).getId());
        tx.executeWithoutResult(s -> cycleCountService.saveCounts(sessionId, Map.of(itemId, 45)));
        tx.executeWithoutResult(s -> cycleCountService.submit(sessionId));

        ACTOR.set("manager");
        race(2, i -> {
            cycleCountService.approve(sessionId);
            return true;
        });

        assertThat(onHandOf(pid)).isEqualTo(45);
        CycleCountStatus status = tx.execute(s -> cycleCountService.findById(sessionId).getStatus());
        int countTransactions = tx.execute(s ->
                transactionRepository.findByReference("COUNT#" + sessionId).size());
        assertThat(status).isEqualTo(CycleCountStatus.APPROVED);
        assertThat(countTransactions).isEqualTo(1);
    }

    @Test
    void 실사가_열려있는_동안_조정은_거부된다() {
        long pid = PID_BASE + 102;
        seedInventory(pid, 30);
        tx.executeWithoutResult(s -> cycleCountService.open(List.of(pid), "조정 차단 확인"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> cycleCountService.assertAdjustable(pid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("실사가 진행 중인 상품");
        assertThat(onHandOf(pid)).isEqualTo(30);
    }
}
