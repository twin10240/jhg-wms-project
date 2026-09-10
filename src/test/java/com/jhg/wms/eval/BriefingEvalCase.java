package com.jhg.wms.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jhg.wms.domain.BriefingSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * 브리핑 평가셋 한 건. 두 종류다.
 *
 * <p><b>정상</b>: 스냅샷만 있고 {@code body}가 null이다. 러너가 생성한다.
 * <p><b>네거티브</b>: 사람이 손으로 망가뜨린 {@code body}가 있고 {@code failingItem}이
 * "이 항목이 떨어져야 한다"를 가리킨다. 생성하지 않는다.
 *
 * <p>네거티브 본문을 모델에게 쓰게 하지 않는 이유: 무엇이 망가졌는지를 모델이 정하게 되고,
 * 그러면 judge를 같은 모델의 판단으로 검증하는 순환이 된다. 사람이 쓴 본문이라야 정답을 안다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BriefingEvalCase(String id, BriefingSnapshot snapshot,
                               String body, String failingItem, String note) {

    public boolean isNegative() {
        return failingItem != null && !failingItem.isBlank();
    }

    public static List<BriefingEvalCase> loadAll(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (var in = BriefingEvalCase.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("평가셋 없음: " + resourcePath);
            Map<String, List<BriefingEvalCase>> root =
                    mapper.readValue(in, new TypeReference<>() {});
            return root.get("cases");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
