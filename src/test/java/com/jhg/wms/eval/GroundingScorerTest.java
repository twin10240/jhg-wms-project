package com.jhg.wms.eval;

import com.jhg.wms.domain.BriefingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채점기가 틀리면 모든 점수가 틀린다. 모델 없이 도는 확정적 로직이라 여기서 다 가둔다.
 */
class GroundingScorerTest {

    // 일평균 3.2333, 소진 예상 4.6875, 가용 15, 출고 97, 표본 30일, 직전 발주 #900 · 50개
    private final BriefingSnapshot snapshot = new BriefingSnapshot(
            LocalDate.of(2026, 9, 10),
            List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 15, 4.6875,
                    900L, LocalDate.of(2026, 9, 1), 50)));

    @Test
    void 표에_있는_값을_그대로_쓰면_통과한다() {
        var r = GroundingScorer.score("가용 15개이고 최근 출고는 97개입니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.clean()).isTrue();
    }

    // 렌더가 소수 1자리로 주므로 모델이 "3.2"라고 쓰는 것이 정상 인용이다.
    @Test
    void 소수_한_자리로_반올림한_인용은_통과한다() {
        var r = GroundingScorer.score("일평균 3.2개씩 나갑니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 정수로_반올림한_인용도_통과한다() {
        var r = GroundingScorer.score("하루 3개쯤 나갑니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 표에_없는_숫자는_잡는다() {
        var r = GroundingScorer.score("일평균 7.4개입니다.", snapshot);

        assertThat(r.ungrounded()).containsExactly("7.4");
        assertThat(r.clean()).isFalse();
    }

    // 계산 금지가 프롬프트에 명시돼 있으므로 파생값은 오탐이 아니라 진짜 위반이다.
    @Test
    void 두_값을_더한_파생값은_잡는다() {
        var r = GroundingScorer.score("합쳐서 112개를 봐야 합니다.", snapshot);   // 97 + 15

        assertThat(r.ungrounded()).containsExactly("112");
    }

    // 발주 번호는 반올림 대상이 아니다. #900을 #3으로 반올림해 통과시키면 안 된다.
    @Test
    void 발주_번호와_상품_번호는_정확히_일치해야_한다() {
        var ok = GroundingScorer.score("직전 발주 #900은 50개였습니다.", snapshot);
        var no = GroundingScorer.score("직전 발주 #901을 보세요.", snapshot);

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).containsExactly("901");
    }

    // 창 길이 30일과 상위 5개는 표에 없지만 환각이 아니다. 화이트리스트로 뺀다.
    // 다만 넓히면 진짜 환각도 통과하므로 이 둘만 둔다.
    @Test
    void 창_길이와_목록_개수는_화이트리스트다() {
        var r = GroundingScorer.score("최근 30일 기준 상위 5개 중에서는", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 날짜는_표의_날짜와_맞아야_한다() {
        var ok = GroundingScorer.score("9월 1일에 발주했습니다.", snapshot);
        var no = GroundingScorer.score("9월 5일에 발주했습니다.", snapshot);

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).contains("5");
    }

    @Test
    void 숫자가_하나도_없으면_total이_0이고_clean이다() {
        var r = GroundingScorer.score("지금 급한 상품은 없습니다.", snapshot);

        assertThat(r.total()).isZero();
        assertThat(r.clean()).isTrue();
    }
}
