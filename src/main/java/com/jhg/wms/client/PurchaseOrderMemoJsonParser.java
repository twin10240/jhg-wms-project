package com.jhg.wms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.PurchaseOrderMemoCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 발주 메모 분류 응답 JSON을 값으로 읽는다.
 *
 * {@link ClassificationJsonParser}와 같은 규칙을 따른다 — SDK를 모르고, 구조화 출력을 쓰더라도
 * 여기서 한 번 더 막는다(스키마가 보장하지 못하는 잘린 응답이 남는다). 필드 구성이 달라
 * (처분 제안이 없다) 클래스는 나누되, 읽기 helper와 로그 안전 규칙은 그쪽 것을 그대로 쓴다.
 */
@Slf4j
@RequiredArgsConstructor
public class PurchaseOrderMemoJsonParser {

    private final ObjectMapper objectMapper;

    public Optional<Parsed> parse(String json) {
        if (json == null || json.isBlank()) {
            log.warn("발주 메모 분류 응답이 비어 있음(무시)");
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            PurchaseOrderMemoCategory category = ClassificationJsonParser.enumOf(
                    PurchaseOrderMemoCategory.class, ClassificationJsonParser.text(node, "category"));
            Confidence confidence = ClassificationJsonParser.enumOf(
                    Confidence.class, ClassificationJsonParser.text(node, "confidence"));

            if (category == null || confidence == null) {
                // 값이 아니라 필드 이름만 남긴다 — 메모 원문이 로그로 새어나가지 않게.
                log.warn("발주 메모 분류 응답이 스키마를 벗어남(무시), 문제 필드: {}",
                        (category == null ? "category " : "") + (confidence == null ? "confidence" : ""));
                return Optional.empty();
            }
            return Optional.of(new Parsed(category, confidence,
                    ClassificationJsonParser.text(node, "evidence")));
        } catch (Exception e) {
            // e.getMessage()는 남기지 않는다 — Jackson 파싱 예외 메시지는 입력 일부를 그대로 인용한다.
            log.warn("발주 메모 분류 응답 파싱 실패(무시): {} (응답 길이 {})",
                    e.getClass().getSimpleName(), json.length());
            return Optional.empty();
        }
    }

    public record Parsed(PurchaseOrderMemoCategory category, Confidence confidence, String evidence) {}
}
