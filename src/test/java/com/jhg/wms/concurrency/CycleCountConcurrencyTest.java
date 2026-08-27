package com.jhg.wms.concurrency;

import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.service.CycleCountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("실사 세션 개설의 동시성")
class CycleCountConcurrencyTest extends ConcurrencySupport {

    @Autowired CycleCountService cycleCountService;
    @Autowired CycleCountRepository cycleCountRepository;

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
}
