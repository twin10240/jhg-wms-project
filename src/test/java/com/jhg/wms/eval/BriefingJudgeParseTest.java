package com.jhg.wms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge 응답 파싱만 검증한다. API 호출 없이 돈다.
 */
class BriefingJudgeParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 세_항목을_모두_읽는다() {
        var parsed = BriefingJudge.parse(mapper,
                "{\"REASONED\":true,\"NO_INVENTED_FACTS\":false,\"ACTIONABLE\":true}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).containsEntry("REASONED", true)
                .containsEntry("NO_INVENTED_FACTS", false)
                .containsEntry("ACTIONABLE", true);
    }

    // 항목이 빠진 응답을 반만 읽으면 없는 항목이 조용히 통과한다. 통째로 버린다.
    @Test
    void 항목이_빠지면_empty다() {
        var parsed = BriefingJudge.parse(mapper, "{\"REASONED\":true}");

        assertThat(parsed).isEmpty();
    }

    @Test
    void 깨진_JSON은_empty다() {
        assertThat(BriefingJudge.parse(mapper, "이건 JSON이 아니다")).isEmpty();
    }
}
