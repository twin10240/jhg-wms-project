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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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

    // 생성기 구현체가 예외를 던져도(타임아웃·직렬화 오류 등) 예외가 밖으로 새지 않는다.
    @Test
    void 생성기가_예외를_던지면_저장하지_않고_false를_낸다() {
        var service = service(s -> { throw new RuntimeException("Claude 호출 실패"); });

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isFalse();
        assertThat(repository.findFirstByOrderByCreatedAtDesc()).isEmpty();
    }

    // ClaudePurchaseOrderBriefingGenerator.generate는 내부에서 모든 예외를 삼켜 절대 던지지 않는다.
    // 그래서 "브리핑이 없어도 발주 업무는 돈다"는 계약이 실제로 기대는 곳은 생성이 아니라
    // 저장 경로(objectMapper.writeValueAsString / repository.save)다 — 그쪽이 던져도 안전한지 본다.
    @Test
    void 저장이_실패해도_예외가_새지_않고_false를_낸다() {
        var failingRepository = mock(PurchaseOrderBriefingRepository.class);
        given(failingRepository.save(any())).willThrow(new RuntimeException("DB 연결 끊김"));
        var service = new PurchaseOrderBriefingService(
                s -> Optional.of(new PurchaseOrderBriefingGenerator.Briefing("볼펜을 먼저 넣으세요.", "haiku", 400, 90)),
                failingRepository, objectMapper);

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isFalse();
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
