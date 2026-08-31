package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

import java.util.Optional;

/**
 * 반품 사유 텍스트 분류기.
 * 인터페이스를 두는 이유는 둘이다 — 서비스가 특정 SDK에 묶이지 않게,
 * 그리고 테스트가 실제 API를 호출하지 않아도 되게.
 * 실패(타임아웃·스키마 위반·키 미설정)는 전부 empty다. 예외로 알리지 않는다.
 */
public interface ReturnReasonClassifier {

    Optional<Classification> classify(String reason);

    record Classification(ReturnCategory category,
                          Confidence confidence,
                          String evidence,
                          RmaDisposition suggestedDisposition,
                          String model,
                          int inputTokens,
                          int outputTokens) {}
}
