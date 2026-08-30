package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.springframework.stereotype.Component;

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

    /**
     * Anthropic 어댑터(Task 5)가 붙기 전까지 컨텍스트를 띄우기 위한 자리다.
     * ReturnClassificationService가 이 인터페이스를 생성자 필수값으로 요구하는데
     * 구현 빈이 하나도 없으면, 이 기능과 무관한 다른 전체-컨텍스트 테스트까지 전부
     * NoSuchBeanDefinitionException으로 죽는다(실측: 12개 테스트 클래스, 103건 실패).
     * 항상 empty만 반환해 실제 분류는 절대 하지 않는다. Task 5에서 진짜 어댑터가
     * 빈으로 등록되면 이 자리는 지운다.
     */
    @Component
    class NoOp implements ReturnReasonClassifier {
        @Override
        public Optional<Classification> classify(String reason) {
            return Optional.empty();
        }
    }
}
