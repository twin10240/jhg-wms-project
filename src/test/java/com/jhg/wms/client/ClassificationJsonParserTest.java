package com.jhg.wms.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조화 출력을 쓰더라도 파싱은 방어한다. 검증 대상은 "모델이 스키마를 어겼을 때
 * 우리 코드가 어떻게 행동하는가"이고, 그건 실제 호출로는 재현할 수 없다.
 */
class ClassificationJsonParserTest {

    private final ClassificationJsonParser parser = new ClassificationJsonParser(new ObjectMapper());

    @Test
    void 정상_응답을_값으로_읽는다() {
        String json = """
                {"category":"DAMAGED","confidence":"HIGH",
                 "evidence":"모서리가 깨져 있어요","suggested_disposition":"DISPOSED"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(parsed.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(parsed.evidence()).isEqualTo("모서리가 깨져 있어요");
        assertThat(parsed.suggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
    }

    @Test
    void enum에_없는_값이면_버린다() {
        String json = """
                {"category":"BROKEN_MAYBE","confidence":"HIGH",
                 "evidence":"깨짐","suggested_disposition":"DISPOSED"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    @Test
    void 필수_필드가_없으면_버린다() {
        String json = """
                {"category":"DAMAGED","confidence":"HIGH","evidence":"깨짐"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    // max_tokens에 걸려 응답이 중간에 끊기는 경우.
    @Test
    void 잘린_JSON이면_버린다() {
        assertThat(parser.parse("{\"category\":\"DAMAGED\",\"conf")).isEmpty();
    }

    @Test
    void 빈_응답이면_버린다() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    // 근거는 참고의 참고다. 없어도 분류 자체는 쓸 수 있으므로 통과시킨다.
    @Test
    void 근거가_없어도_분류는_살린다() {
        String json = """
                {"category":"CHANGED_MIND","confidence":"LOW","suggested_disposition":"RESTOCKED"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(ReturnCategory.CHANGED_MIND);
        assertThat(parsed.evidence()).isNull();
    }

    // 필드가 문자열이 아닌 타입으로 오는 경우(스키마 위반의 다른 얼굴).
    @Test
    void 필드_타입이_다르면_버린다() {
        String json = """
                {"category":1,"confidence":"HIGH","evidence":"깨짐","suggested_disposition":"DISPOSED"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    // evidence는 필수값이 아니라서 위의 필드_타입이_다르면_버린다()가 안 잡는다 —
    // isTextual() 가드가 없으면 asText()가 숫자를 그대로 문자열로 밀어 넣는다.
    @Test
    void evidence가_문자열이_아니면_null로_버린다() {
        String json = """
                {"category":"DAMAGED","confidence":"HIGH",
                 "evidence":12345,"suggested_disposition":"DISPOSED"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(parsed.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(parsed.evidence()).isNull();
        assertThat(parsed.suggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
    }
}
