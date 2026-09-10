package com.jhg.wms.service;

import com.jhg.wms.domain.BriefingSnapshot;

import java.util.Optional;

/**
 * 발주 브리핑 생성기. 인터페이스를 두는 이유는 {@link PurchaseOrderMemoClassifier}와 같다 —
 * 서비스가 특정 SDK에 묶이지 않게, 그리고 테스트가 실제 API를 호출하지 않아도 되게.
 * 실패(타임아웃·빈 응답·키 미설정)는 전부 empty다. 예외로 알리지 않는다.
 */
public interface PurchaseOrderBriefingGenerator {

    Optional<Briefing> generate(BriefingSnapshot snapshot);

    record Briefing(String body, String model, int inputTokens, int outputTokens) {}
}
