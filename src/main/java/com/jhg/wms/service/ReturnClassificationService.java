package com.jhg.wms.service;

import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.repository.ReturnClassificationRepository;
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
public class ReturnClassificationService {

    private final ReturnReasonClassifier classifier;
    private final ReturnClassificationRepository repository;

    /**
     * 분류를 시도해 성공하면 저장한다. 접수 트랜잭션이 이미 커밋된 뒤 별도 스레드에서 불린다.
     * 어떤 실패도 밖으로 던지지 않는다 — 분류는 참고 정보라 실패가 다른 것을 망가뜨려선 안 된다.
     *
     * NOT_SUPPORTED로 트랜잭션 밖에서 돈다. 이 메서드를 @Transactional로 두면 첫 DB 접근
     * (아래 존재 확인)에서 잡은 커넥션을 HTTP 호출이 끝날 때까지 붙잡는다 — 최대 40초
     * (timeout 20s × maxRetries 1)다. 스레드가 둘뿐이라 Hikari 10개가 고갈되지는 않지만,
     * 아무것도 하지 않는 커넥션을 그만큼 쥐고 있을 이유가 없다.
     *
     * 트랜잭션이 사라지는 것이 아니라 잘게 나뉜다 — Spring Data의 저장소 메서드가 각자
     * @Transactional이라 존재 확인과 저장이 각각 자기 트랜잭션에서 열리고 즉시 닫힌다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
                        // 트랜잭션이 저장소 메서드 안에서 닫히므로 이 줄은 커밋 뒤에 찍힌다.
                        // (외부 트랜잭션이 있던 시절에는 커밋보다 먼저 찍혀 "돌려줬다"는 증거일 뿐이었다.)
                        log.info("반품 사유 분류: rmaId={} category={} confidence={} model={} in={} out={}",
                                rmaReturnId, c.category(), c.confidence(), c.model(),
                                c.inputTokens(), c.outputTokens());
                    },
                    () -> log.warn("반품 사유 분류 없음(무시): rmaId={}", rmaReturnId));
        } catch (Exception e) {
            // 저장 실패도 이제 여기 걸린다 — 커밋이 이 메서드 안에서 일어나기 때문이다.
            log.warn("반품 사유 분류 실패(무시): rmaId={}", rmaReturnId, e);
        }
    }

    public Optional<ReturnClassification> findByRmaId(Long rmaReturnId) {
        return repository.findByRmaReturnId(rmaReturnId);
    }
}
