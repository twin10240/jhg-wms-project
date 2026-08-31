package com.jhg.wms.service;

import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.repository.ReturnClassificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnClassificationService {

    private final ReturnReasonClassifier classifier;
    private final ReturnClassificationRepository repository;

    /**
     * 분류를 시도해 성공하면 저장한다. 접수 트랜잭션이 이미 커밋된 뒤 별도 스레드에서 불린다.
     * 어떤 실패도 밖으로 던지지 않는다 — 분류는 참고 정보라 실패가 다른 것을 망가뜨려선 안 된다.
     */
    @Transactional
    public void classifyAndSave(Long rmaReturnId, String reason) {
        if (reason == null || reason.isBlank()) return;
        // 유니크 제약이 터지기 전에 막는다. 재실행돼도 토큰을 다시 쓰지 않는다.
        if (repository.existsByRmaReturnId(rmaReturnId)) return;

        try {
            classifier.classify(reason).ifPresentOrElse(
                    c -> {
                        repository.save(ReturnClassification.create(rmaReturnId, c.category(),
                                c.confidence(), c.evidence(), c.suggestedDisposition(),
                                c.model(), c.inputTokens(), c.outputTokens()));
                        // 건당 토큰을 로그에도 남긴다 — 엔티티만 보면 총량을 세기 번거롭다.
                        // 이 줄은 커밋보다 먼저 찍힌다 — save()는 @GeneratedValue(AUTO)라 여기선
                        // em.persist()만 하고 실제 INSERT는 이 메서드가 반환된 뒤 트랜잭션 커밋
                        // 시점에 플러시된다. 즉 이 로그는 "분류기가 값을 돌려줬다"는 증거일 뿐,
                        // "행이 저장됐다"는 증거는 아니다.
                        log.info("반품 사유 분류: rmaId={} category={} confidence={} model={} in={} out={}",
                                rmaReturnId, c.category(), c.confidence(), c.model(),
                                c.inputTokens(), c.outputTokens());
                    },
                    () -> log.warn("반품 사유 분류 없음(무시): rmaId={}", rmaReturnId));
        } catch (Exception e) {
            log.warn("반품 사유 분류 실패(무시): rmaId={}", rmaReturnId, e);
        }
    }

    public Optional<ReturnClassification> findByRmaId(Long rmaReturnId) {
        return repository.findByRmaReturnId(rmaReturnId);
    }
}
