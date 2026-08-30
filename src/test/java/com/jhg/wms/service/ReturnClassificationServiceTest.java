package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import com.jhg.wms.repository.ReturnClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
class ReturnClassificationServiceTest {

    @Autowired ReturnClassificationRepository repository;

    AtomicInteger calls;
    AtomicReference<String> lastReason;

    @BeforeEach
    void setUp() {
        calls = new AtomicInteger();
        lastReason = new AtomicReference<>();
    }

    /** 실제 API를 부르지 않는 가짜 분류기. 인터페이스를 둔 이유가 이것이다. */
    private ReturnClassificationService serviceReturning(
            ReturnReasonClassifier.Classification result) {
        return new ReturnClassificationService(reason -> {
            calls.incrementAndGet();
            lastReason.set(reason);
            return Optional.ofNullable(result);
        }, repository);
    }

    private ReturnReasonClassifier.Classification sample() {
        return new ReturnReasonClassifier.Classification(
                ReturnCategory.DAMAGED, Confidence.HIGH, "모서리가 깨져 있어요",
                RmaDisposition.DISPOSED, "claude-haiku-4-5", 412, 118);
    }

    @Test
    void 분류에_성공하면_저장한다() {
        serviceReturning(sample()).classifyAndSave(11L, "받았는데 모서리가 깨져 있어요");

        var saved = repository.findByRmaReturnId(11L).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(saved.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(saved.getSuggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
        assertThat(saved.getInputTokens()).isEqualTo(412);
        assertThat(saved.getOutputTokens()).isEqualTo(118);
        assertThat(lastReason.get()).isEqualTo("받았는데 모서리가 깨져 있어요");
    }

    @Test
    void 분류에_실패하면_저장하지_않는다() {
        serviceReturning(null).classifyAndSave(12L, "그냥요");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(repository.findByRmaReturnId(12L)).isEmpty();
    }

    // 빈 사유로 부르는 건 확정적으로 쓸모없는 호출이다 — 토큰을 쓰기 전에 막는다.
    @Test
    void 사유가_비어_있으면_분류기를_부르지_않는다() {
        var service = serviceReturning(sample());

        service.classifyAndSave(13L, null);
        service.classifyAndSave(14L, "   ");

        assertThat(calls.get()).isZero();
        assertThat(repository.findByRmaReturnId(13L)).isEmpty();
        assertThat(repository.findByRmaReturnId(14L)).isEmpty();
    }

    // rmaReturnId에 유니크 제약이 걸려 있어, 두 번째 호출이 그대로 들어가면
    // 배경 스레드에서 제약 위반으로 터진다. 부르기 전에 막는다.
    @Test
    void 이미_분류가_있으면_다시_부르지_않는다() {
        var service = serviceReturning(sample());
        service.classifyAndSave(15L, "깨졌어요");
        service.classifyAndSave(15L, "깨졌어요");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
    }

    // 분류는 참고 정보다. 분류기가 뭘 던지든 그게 호출자(접수 경로)로 새면 안 된다.
    @Test
    void 분류기가_예외를_던져도_밖으로_새지_않는다() {
        var service = new ReturnClassificationService(reason -> {
            throw new IllegalStateException("모델 호출 폭발");
        }, repository);

        assertThatCode(() -> service.classifyAndSave(16L, "깨졌어요"))
                .doesNotThrowAnyException();
        assertThat(repository.findByRmaReturnId(16L)).isEmpty();
    }

    @Test
    void 저장된_분류를_rmaId로_찾는다() {
        var service = serviceReturning(sample());
        service.classifyAndSave(17L, "깨졌어요");

        assertThat(service.findByRmaId(17L)).isPresent();
        assertThat(service.findByRmaId(999L)).isEmpty();
    }
}
