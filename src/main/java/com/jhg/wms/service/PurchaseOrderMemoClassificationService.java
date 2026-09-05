package com.jhg.wms.service;

import com.jhg.wms.domain.PurchaseOrderMemoClassification;
import com.jhg.wms.repository.PurchaseOrderMemoClassificationRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderMemoClassificationService {

    private final PurchaseOrderMemoClassifier classifier;
    private final PurchaseOrderMemoClassificationRepository repository;
    private final ReplenishmentRequestRepository requestRepository;

    /**
     * 분류를 시도해 성공하면 저장한다. 발주 트랜잭션이 이미 커밋된 뒤 별도 스레드에서 불린다.
     * 어떤 실패도 밖으로 던지지 않는다 — 분류는 참고 정보라 실패가 다른 것을 망가뜨려선 안 된다.
     *
     * NOT_SUPPORTED로 트랜잭션 밖에서 돈다. 이유는 {@link ReturnClassificationService}와 같다 —
     * 여기를 @Transactional로 두면 첫 DB 접근에서 잡은 커넥션을 HTTP 호출이 끝날 때까지
     * (최대 40초) 붙잡는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void classifyAndSave(Long purchaseOrderId, String memo) {
        if (memo == null || memo.isBlank()) return;
        // 유니크 제약이 터지기 전에 막는다. 재실행돼도 토큰을 다시 쓰지 않는다.
        if (repository.existsByPurchaseOrderId(purchaseOrderId)) return;

        // OMS 보충 승인으로 난 발주은 분류하지 않는다. 그 메모는 사람이 쓴 것이 아니라
        // ReplenishmentRequestService.approve()가 "OMS 보충 요청 #N - {reason}"으로 조립한
        // 문자열이라, 접두어가 모든 건에 똑같이 들어가고 안쪽 문구도 창고가 아니라 OMS가 쓴 것이다.
        //
        // 문자열 접두어를 보고 거르지 않는다 — 사람이 메모에 같은 말을 쓰면 걸린다.
        // 보충 요청과 연결됐는지로 판별한다. approve()가 링크를 남기는 유일한 경로다.
        // (커밋 뒤에 불리므로 그 링크는 이 시점에 이미 보인다.)
        if (requestRepository.findByPurchaseOrderId(purchaseOrderId).isPresent()) {
            log.debug("OMS 보충 경로 발주라 메모를 분류하지 않습니다: poId={}", purchaseOrderId);
            return;
        }

        try {
            classifier.classify(memo).ifPresentOrElse(
                    c -> {
                        repository.save(PurchaseOrderMemoClassification.create(purchaseOrderId,
                                c.category(), c.confidence(), c.evidence(),
                                c.model(), c.inputTokens(), c.outputTokens()));
                        // 건당 토큰을 로그에도 남긴다 — 엔티티만 보면 총량을 세기 번거롭다.
                        log.info("발주 메모 분류: poId={} category={} confidence={} model={} in={} out={}",
                                purchaseOrderId, c.category(), c.confidence(), c.model(),
                                c.inputTokens(), c.outputTokens());
                    },
                    () -> log.warn("발주 메모 분류 없음(무시): poId={}", purchaseOrderId));
        } catch (Exception e) {
            log.warn("발주 메모 분류 실패(무시): poId={}", purchaseOrderId, e);
        }
    }

    public Optional<PurchaseOrderMemoClassification> findByPurchaseOrderId(Long purchaseOrderId) {
        return repository.findByPurchaseOrderId(purchaseOrderId);
    }
}
