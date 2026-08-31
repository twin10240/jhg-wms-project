package com.jhg.wms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 모델 응답 JSON을 값으로 읽는다. SDK를 모른다 — 고정 JSON으로 단독 검증하기 위해서다.
 * 구조화 출력을 쓰더라도 여기서 한 번 더 막는다: 스키마가 보장하지 못하는 것(잘린 응답)이
 * 남아 있고, 스키마를 어겼을 때 우리 코드가 어떻게 행동하는지는 실제 호출로 재현할 수 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class ClassificationJsonParser {

    private final ObjectMapper objectMapper;

    public Optional<Parsed> parse(String json) {
        if (json == null || json.isBlank()) {
            log.warn("분류 응답이 비어 있음(무시)");
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            ReturnCategory category = enumOf(ReturnCategory.class, text(node, "category"));
            Confidence confidence = enumOf(Confidence.class, text(node, "confidence"));
            RmaDisposition disposition =
                    enumOf(RmaDisposition.class, text(node, "suggested_disposition"));

            if (category == null || confidence == null || disposition == null) {
                log.warn("분류 응답이 스키마를 벗어남(무시), 문제 필드: {}",
                        invalidFields(category, confidence, disposition));
                return Optional.empty();
            }
            return Optional.of(new Parsed(category, confidence, text(node, "evidence"), disposition));
        } catch (Exception e) {
            // e.getMessage()는 남기지 않는다 — Jackson 파싱 예외 메시지는 입력 일부를
            // 그대로 인용한다(예: `at [Source: (String)"..."; line: 1, column: 28]`).
            log.warn("분류 응답 파싱 실패(무시): {} (응답 길이 {})",
                    e.getClass().getSimpleName(), json.length());
            return Optional.empty();
        }
    }

    // 로그에 응답 값을 남기지 않는다 — 사유 원문이 인용돼 들어올 수 있다.
    // 어떤 필드가 문제인지 필드 "이름"만 남기고, 값은 절대 로그에 넣지 않는다.
    private static String invalidFields(Object category, Object confidence, Object disposition) {
        StringBuilder sb = new StringBuilder();
        if (category == null) sb.append("category ");
        if (confidence == null) sb.append("confidence ");
        if (disposition == null) sb.append("suggested_disposition ");
        return sb.toString().trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || !value.isTextual()) ? null : value.asText();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record Parsed(ReturnCategory category,
                         Confidence confidence,
                         String evidence,
                         RmaDisposition suggestedDisposition) {}
}
