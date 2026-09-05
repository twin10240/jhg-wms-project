package com.jhg.wms.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.PurchaseOrderMemoCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClassificationJsonParserTest}와 같은 이유로 둔다 — 구조화 출력을 쓰더라도
 * 모델이 스키마를 어겼을 때 우리 코드가 어떻게 행동하는지는 실제 호출로 재현할 수 없다.
 */
class PurchaseOrderMemoJsonParserTest {

    private final PurchaseOrderMemoJsonParser parser = new PurchaseOrderMemoJsonParser(new ObjectMapper());

    @Test
    void 정상_응답을_값으로_읽는다() {
        String json = """
                {"category":"URGENT_STOCKOUT","confidence":"HIGH","evidence":"결품 임박"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(PurchaseOrderMemoCategory.URGENT_STOCKOUT);
        assertThat(parsed.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(parsed.evidence()).isEqualTo("결품 임박");
    }

    @Test
    void enum에_없는_범주면_버린다() {
        String json = """
                {"category":"SEASONAL","confidence":"HIGH","evidence":"겨울 준비"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    @Test
    void 필드가_빠지면_버린다() {
        assertThat(parser.parse("""
                {"category":"ROUTINE","evidence":"정기"}
                """)).isEmpty();
    }

    @Test
    void 잘린_응답이면_버린다() {
        // 스키마가 막지 못하는 것 — max_tokens에 걸려 중간에서 끊긴 JSON.
        assertThat(parser.parse("{\"category\":\"ROUTINE\",\"confi")).isEmpty();
    }

    @Test
    void 비었거나_null이면_버린다() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void 근거가_없어도_범주와_신뢰도가_있으면_읽는다() {
        // evidence는 인용할 대목이 없으면 빈 문자열이라고 프롬프트가 정해 뒀다.
        var parsed = parser.parse("""
                {"category":"OTHER","confidence":"LOW","evidence":""}
                """).orElseThrow();

        assertThat(parsed.category()).isEqualTo(PurchaseOrderMemoCategory.OTHER);
        assertThat(parsed.evidence()).isEmpty();
    }
}
