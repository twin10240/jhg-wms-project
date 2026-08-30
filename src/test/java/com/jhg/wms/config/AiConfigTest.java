package com.jhg.wms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.service.ReturnReasonClassifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 API를 부르지 않는다 — 검증 대상은 "키가 없을 때 기동이 막히지 않는가"다.
 * wms.basic·oms.callback은 없으면 통신이 전면 실패하므로 기동을 막지만,
 * 분류는 없어도 창고 업무가 돈다.
 */
class AiConfigTest {

    private final AiConfig config = new AiConfig();

    @Test
    void 키가_없으면_항상_빈_결과를_내는_분류기가_된다() {
        ReturnReasonClassifier classifier = config.returnReasonClassifier(
                "", "claude-haiku-4-5", 1024L, Duration.ofSeconds(20), new ObjectMapper());

        assertThat(classifier.classify("모서리가 깨져 있어요")).isEmpty();
    }

    @Test
    void 키가_공백만_있어도_비활성이다() {
        ReturnReasonClassifier classifier = config.returnReasonClassifier(
                "   ", "claude-haiku-4-5", 1024L, Duration.ofSeconds(20), new ObjectMapper());

        assertThat(classifier.classify("깨졌어요")).isEmpty();
    }
}
