package com.jhg.wms.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudeReturnReasonClassifier;
import com.jhg.wms.service.ReturnReasonClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Configuration
public class AiConfig {

    /**
     * API 키가 없으면 항상 empty를 내는 구현으로 대체한다.
     * wms.basic·oms.callback은 없으면 통신이 전면 실패하므로 prod에서 기동을 막지만,
     * 분류는 없어도 창고 업무가 돌아간다 — 여기서 기동을 막으면 잃는 것이 더 크다.
     */
    @Bean
    public ReturnReasonClassifier returnReasonClassifier(
            @Value("${wms.ai.api-key:}") String apiKey,
            @Value("${wms.ai.model}") String model,
            @Value("${wms.ai.max-tokens}") long maxTokens,
            @Value("${wms.ai.timeout}") Duration timeout,
            ObjectMapper objectMapper) {

        if (apiKey == null || apiKey.isBlank()) {
            log.info("ANTHROPIC_API_KEY 미설정 — 반품 사유 자동 분류를 끈 채로 기동합니다.");
            return reason -> Optional.empty();
        }

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // SDK 기본 타임아웃은 10분이다. 참고 정보 하나 때문에 스레드를 그만큼 붙잡을 이유가 없다.
                .timeout(timeout)
                // 실패해도 재시도 스윕이 없는 설계라 여기서 한 번만 더 시도한다.
                .maxRetries(1)
                .build();
        log.info("반품 사유 자동 분류 활성: model={} maxTokens={} timeout={}", model, maxTokens, timeout);
        return new ClaudeReturnReasonClassifier(client, objectMapper, model, maxTokens);
    }
}
