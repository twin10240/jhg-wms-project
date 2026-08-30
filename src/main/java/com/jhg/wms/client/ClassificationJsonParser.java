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
                log.warn("분류 응답이 스키마를 벗어남(무시): {}", abbreviate(json));
                return Optional.empty();
            }
            return Optional.of(new Parsed(category, confidence, text(node, "evidence"), disposition));
        } catch (Exception e) {
            log.warn("분류 응답 파싱 실패(무시): {}", abbreviate(json));
            return Optional.empty();
        }
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

    // 로그에 응답 전문을 쏟지 않는다 — 사유 원문이 인용돼 들어올 수 있다.
    private static String abbreviate(String json) {
        return json.length() <= 200 ? json : json.substring(0, 200) + "…";
    }

    public record Parsed(ReturnCategory category,
                         Confidence confidence,
                         String evidence,
                         RmaDisposition suggestedDisposition) {}
}
