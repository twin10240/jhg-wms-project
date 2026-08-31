package com.jhg.wms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 생성자가 하는 일(프롬프트·스키마 리소스 로딩과 스키마 파싱)만 검증한다. API는 부르지 않는다.
 *
 * 이 클래스는 키가 설정됐을 때만 만들어진다. 그래서 리소스 파일이 사라지거나 스키마에 오타가 나면
 * 지금까지는 **키가 설정된 환경의 기동에서만** 드러났다. 오프라인으로 확인할 수 있는 실패를
 * 운영 기동까지 미룰 이유가 없다.
 */
class ClaudeReturnReasonClassifierTest {

    private static final String SCHEMA = "prompts/return-classification-schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * client는 classify()에서만 쓰이고 생성자는 건드리지 않는다 —
     * null을 넘기는 것이 "생성자만 본다"는 이 테스트의 범위를 그대로 드러낸다.
     */
    @Test
    void 프롬프트와_스키마를_읽어_생성된다() {
        assertThatCode(() -> new ClaudeReturnReasonClassifier(
                null, objectMapper, "claude-haiku-4-5", 1024L))
                .doesNotThrowAnyException();
    }

    /**
     * 스키마의 enum 목록과 자바 enum이 어긋나면 조용히 반쪽이 된다 —
     * 스키마에만 있는 값은 파서가 거부하고(스키마 이탈), 자바에만 있는 값은 모델이 낼 수 없다.
     * 둘 다 실행 중에야 드러나므로 여기서 고정한다.
     */
    @Test
    void 스키마의_enum이_자바_enum과_일치한다() throws Exception {
        JsonNode properties = schema().get("properties");

        assertThat(enumValues(properties, "category"))
                .containsExactlyInAnyOrderElementsOf(names(ReturnCategory.values()));
        assertThat(enumValues(properties, "confidence"))
                .containsExactlyInAnyOrderElementsOf(names(Confidence.values()));
        assertThat(enumValues(properties, "suggested_disposition"))
                .containsExactlyInAnyOrderElementsOf(names(RmaDisposition.values()));
    }

    /** 네 필드가 모두 required여야 파서가 값을 기대할 수 있다. 하나라도 빠지면 스키마 이탈로 떨어진다. */
    @Test
    void 네_필드가_모두_required다() throws Exception {
        assertThat(textValues(schema().get("required")))
                .containsExactlyInAnyOrder("category", "confidence", "evidence", "suggested_disposition");
    }

    private JsonNode schema() throws Exception {
        try (var in = new ClassPathResource(SCHEMA).getInputStream()) {
            return objectMapper.readTree(StreamUtils.copyToString(in, StandardCharsets.UTF_8));
        }
    }

    private List<String> enumValues(JsonNode properties, String field) {
        return textValues(properties.get(field).get("enum"));
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
