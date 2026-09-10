package com.jhg.wms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.domain.PurchaseOrderBriefing;
import com.jhg.wms.repository.PurchaseOrderBriefingRepository;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 브리핑 생성과 저장.
 *
 * <p>분류 둘과 달리 <b>동기</b>다. 반품·메모 분류는 커밋 후 별도 스레드에서 돌고 사용자는
 * 기다리지 않지만, 브리핑은 사용자가 버튼을 누르고 결과를 기다리는 읽기 시점이라
 * 비동기 트리거 패턴을 쓸 수 없다.
 *
 * <p>그래도 커넥션 문제는 같다. {@code generateAndSave}를 통째로 {@code @Transactional}로
 * 두면 HTTP 호출이 끝날 때까지(최대 60초) DB 커넥션을 붙잡는다. 그래서 {@code NOT_SUPPORTED}로
 * 트랜잭션 밖에서 돌고, 저장은 {@code repository.save()}가 여는 자기 트랜잭션에 맡긴다 —
 * 커넥션을 잡는 구간이 저장 한 번으로 좁아진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderBriefingService {

    // ponytail: 상위 5개는 근거 없는 값이다. 브리핑이 흐리면 줄이고, 놓치는 상품이 보이면 늘린다.
    public static final int TOP_N = 5;

    private final PurchaseOrderBriefingGenerator generator;
    private final PurchaseOrderBriefingRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @return 저장에 성공했으면 true. 실패는 예외가 아니라 false다 —
     *         브리핑이 없어도 발주 업무는 돌아야 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean generateAndSave(LocalDate today, List<ProductAdvice> advice) {
        if (advice == null || advice.isEmpty()) {
            log.debug("근거가 비어 브리핑을 만들지 않습니다.");
            return false;
        }

        BriefingSnapshot snapshot = BriefingSnapshot.of(today, advice, TOP_N);
        try {
            Optional<PurchaseOrderBriefingGenerator.Briefing> result = generator.generate(snapshot);
            if (result.isEmpty()) {
                log.warn("발주 브리핑 없음(무시)");
                return false;
            }
            var b = result.get();
            repository.save(PurchaseOrderBriefing.create(b.body(), objectMapper.writeValueAsString(snapshot),
                    b.model(), b.inputTokens(), b.outputTokens()));
            log.info("발주 브리핑 생성: model={} in={} out={}", b.model(), b.inputTokens(), b.outputTokens());
            return true;
        } catch (Exception e) {
            log.warn("발주 브리핑 실패(무시)", e);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseOrderBriefing> findLatest() {
        return repository.findFirstByOrderByCreatedAtDesc();
    }
}
