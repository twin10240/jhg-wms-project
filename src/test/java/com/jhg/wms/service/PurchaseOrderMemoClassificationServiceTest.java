package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.PurchaseOrderMemoCategory;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.domain.ReplenishmentRequestItem;
import com.jhg.wms.repository.PurchaseOrderMemoClassificationRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.service.PurchaseOrderMemoClassifier.Classification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분류기는 스텁이다 — 실제 API를 부르지 않는다. 여기서 재는 것은 모델의 정확도가 아니라
 * <b>무엇을 분류 대상으로 삼는가</b>이고, 그건 우리 코드의 규칙이라 결정적으로 검증할 수 있다.
 * (모델 정확도는 evalTest 소관이다.)
 */
@DataJpaTest
class PurchaseOrderMemoClassificationServiceTest {

    @Autowired PurchaseOrderMemoClassificationRepository repository;
    @Autowired ReplenishmentRequestRepository requestRepo;

    /** 부른 횟수를 세는 스텁. "부르지 않았다"를 검증해야 해서 호출 수가 필요하다. */
    static class 세는분류기 implements PurchaseOrderMemoClassifier {
        int calls;
        Optional<Classification> result = Optional.of(new Classification(
                PurchaseOrderMemoCategory.ROUTINE, Confidence.HIGH, "정기 보충", "stub-model", 10, 5));

        @Override
        public Optional<Classification> classify(String memo) {
            calls++;
            return result;
        }
    }

    세는분류기 classifier;
    PurchaseOrderMemoClassificationService service;

    @BeforeEach
    void setUp() {
        classifier = new 세는분류기();
        service = new PurchaseOrderMemoClassificationService(classifier, repository, requestRepo);
    }

    /** OMS 보충 요청을 승인해 발주가 났을 때의 상태 — 요청이 발주 id를 들고 있다. */
    private void 보충요청_승인(Long purchaseOrderId) {
        ReplenishmentRequest request = ReplenishmentRequest.create(
                UUID.randomUUID(), "백오더 보충",
                ReplenishmentRequestItem.create(1L, 10));
        request.approve(purchaseOrderId, "승인");
        requestRepo.save(request);
    }

    @Test
    void 수동_발주_메모는_분류해_저장한다() {
        service.classifyAndSave(1L, "10번 결품 임박. 오늘 중 발주 필요");

        assertThat(classifier.calls).isEqualTo(1);
        var saved = repository.findByPurchaseOrderId(1L).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo(PurchaseOrderMemoCategory.ROUTINE);
        assertThat(saved.getModel()).isEqualTo("stub-model");
        assertThat(saved.getInputTokens()).isEqualTo(10);
    }

    @Test
    void OMS_보충으로_난_발주는_분류하지_않는다() {
        보충요청_승인(2L);

        // 이 메모는 approve()가 조립한 형태다. 사람이 쓴 것이 아니라 접두어가 늘 같고,
        // 안쪽 문구도 창고가 아니라 OMS가 쓴 것이라 분류 모수에 들어가면 안 된다.
        service.classifyAndSave(2L, "OMS 보충 요청 #1 - 백오더 보충");

        assertThat(classifier.calls).isZero();   // 토큰을 아예 쓰지 않는다
        assertThat(repository.findByPurchaseOrderId(2L)).isEmpty();
    }

    @Test
    void 접두어가_같아도_보충요청과_연결되지_않았으면_분류한다() {
        // 문자열로 거르면 사람이 같은 말을 쓴 메모까지 걸린다. 판별 기준은 연결 여부다.
        service.classifyAndSave(3L, "OMS 보충 요청 건과 별개로 추가 발주합니다");

        assertThat(classifier.calls).isEqualTo(1);
        assertThat(repository.findByPurchaseOrderId(3L)).isPresent();
    }

    @Test
    void 이미_분류된_발주는_다시_부르지_않는다() {
        service.classifyAndSave(4L, "정기 보충");
        service.classifyAndSave(4L, "정기 보충");

        assertThat(classifier.calls).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void 빈_메모는_부르지도_저장하지도_않는다() {
        service.classifyAndSave(5L, "   ");
        service.classifyAndSave(6L, null);

        assertThat(classifier.calls).isZero();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void 분류기가_실패하면_저장하지_않고_예외도_내지_않는다() {
        classifier.result = Optional.empty();

        service.classifyAndSave(7L, "결품 임박");

        assertThat(repository.findByPurchaseOrderId(7L)).isEmpty();
    }

    @Test
    void 근거가_컬럼_길이를_넘으면_잘라_저장한다() {
        classifier.result = Optional.of(new Classification(PurchaseOrderMemoCategory.OTHER,
                Confidence.LOW, "가".repeat(600), "stub-model", 1, 1));

        service.classifyAndSave(8L, "메모");

        assertThat(repository.findByPurchaseOrderId(8L).orElseThrow().getEvidence()).hasSize(500);
    }

    @Test
    void 분류는_발주와_일대일이다() {
        service.classifyAndSave(9L, "정기");
        service.classifyAndSave(10L, "정기");

        assertThat(repository.findAll()).hasSize(2);
        assertThat(List.of(9L, 10L)).allSatisfy(id ->
                assertThat(repository.existsByPurchaseOrderId(id)).isTrue());
    }
}
