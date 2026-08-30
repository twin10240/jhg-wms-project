package com.jhg.wms.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.service.ReturnReasonClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class ClaudeReturnReasonClassifier implements ReturnReasonClassifier {

    private final AnthropicClient client;
    private final ClassificationJsonParser parser;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;
    private final JsonOutputFormat.Schema schema;

    public ClaudeReturnReasonClassifier(AnthropicClient client, ObjectMapper objectMapper,
                                        String model, long maxTokens) {
        this.client = client;
        this.parser = new ClassificationJsonParser(objectMapper);
        this.model = model;
        this.maxTokens = maxTokens;
        // 프롬프트·스키마는 기동 시 한 번 읽는다. 파일이 없으면 그건 배포 사고라 기동에서 드러나야 한다.
        this.systemPrompt = readResource("prompts/return-classification.txt");
        this.schema = toSchema(objectMapper, readResource("prompts/return-classification-schema.json"));
    }

    @Override
    public Optional<Classification> classify(String reason) {
        if (reason == null || reason.isBlank()) return Optional.empty();

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    // 자유 텍스트를 파싱하지 않는다 — 비결정적 응답을 스키마 안에 가두는 것이 이 기능의 핵심이다.
                    .outputConfig(OutputConfig.builder()
                            .format(JsonOutputFormat.builder().schema(schema).build())
                            .build())
                    .addUserMessage(reason)
                    .build();

            Message message = client.messages().create(params);
            String json = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());

            return parser.parse(json).map(parsed -> new Classification(
                    parsed.category(), parsed.confidence(), parsed.evidence(),
                    parsed.suggestedDisposition(),
                    message.model().asString(),
                    (int) message.usage().inputTokens(),
                    (int) message.usage().outputTokens()));
        } catch (Exception e) {
            // 타임아웃·연결 실패·SDK 예외를 전부 여기서 끊는다 — 인터페이스 계약이 "실패는 empty"다.
            log.warn("반품 사유 분류 호출 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }

    private static String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("분류 리소스를 읽지 못했습니다: " + path, e);
        }
    }

    /** Schema는 자유형 맵이라 JSON 스키마 파일을 그대로 얹는다. */
    private static JsonOutputFormat.Schema toSchema(ObjectMapper objectMapper, String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
            map.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("분류 스키마가 올바른 JSON이 아닙니다.", e);
        }
    }
}
