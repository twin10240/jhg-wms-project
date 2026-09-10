package com.jhg.wms.service;

import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.domain.PurchaseOrderBriefing;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.repository.PurchaseOrderBriefingRepository;
import com.jhg.wms.service.PurchaseOrderAdviceService.LastOrder;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PurchaseOrderBriefingServiceTest {

    @Autowired private PurchaseOrderBriefingRepository repository;
    @Autowired private ObjectMapper objectMapper;

    private ProductAdvice advice(long id, String name) {
        return new ProductAdvice(id, name, 60, 30L, 2.0, 20, 5, 15, 7.5,
                new LastOrder(900L, PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 9, 1), 50,
                        LocalDate.of(2026, 9, 3), 2L));
    }

    private PurchaseOrderBriefingService service(PurchaseOrderBriefingGenerator generator) {
        return new PurchaseOrderBriefingService(generator, repository, objectMapper);
    }

    @Test
    void 생성에_성공하면_본문과_스냅샷을_같이_저장한다() {
        var service = service(s -> Optional.of(
                new PurchaseOrderBriefingGenerator.Briefing("볼펜을 먼저 넣으세요.", "haiku", 400, 90)));

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isTrue();
        var stored = repository.findFirstByOrderByCreatedAtDesc().orElseThrow();
        assertThat(stored.getBody()).isEqualTo("볼펜을 먼저 넣으세요.");
        assertThat(stored.getInputSnapshot()).contains("\"productId\":1");
    }

    // 생성이 실패해도 예외가 밖으로 나가지 않는다. 브리핑이 없어도 발주 업무는 돈다.
    @Test
    void 생성에_실패하면_저장하지_않고_false를_낸다() {
        var service = service(s -> Optional.empty());

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isFalse();
        assertThat(repository.findFirstByOrderByCreatedAtDesc()).isEmpty();
    }

    // 근거가 아예 없으면 모델을 부르지 않는다 — 부를 이유도 없고 토큰만 쓴다.
    @Test
    void 근거가_비면_모델을_부르지_않는다() {
        var 불림 = new boolean[]{false};
        var service = service(s -> { 불림[0] = true; return Optional.empty(); });

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of());

        assertThat(saved).isFalse();
        assertThat(불림[0]).isFalse();
    }

    @Test
    void 상위_다섯_개만_스냅샷에_담는다() {
        var captured = new BriefingSnapshot[1];
        var service = service(s -> {
            captured[0] = s;
            return Optional.of(new PurchaseOrderBriefingGenerator.Briefing("x", "haiku", 1, 1));
        });

        service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(
                advice(1, "A"), advice(2, "B"), advice(3, "C"),
                advice(4, "D"), advice(5, "E"), advice(6, "F")));

        assertThat(captured[0].rows()).hasSize(PurchaseOrderBriefingService.TOP_N);
    }
}
