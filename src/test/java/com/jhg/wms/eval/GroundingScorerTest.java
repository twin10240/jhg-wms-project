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

    // 렌더는 소수 1자리로 보여준다(3.2333 → "3.2"). "3"은 모델이 스스로 반올림해 지어낸
    // 자릿수지 표의 값이 아니다 — 세 차례 재발한 세탁 버그(발주 #3, 가용은 5개)의 근본
    // 원인이 바로 이 정수 반올림을 인정하던 규칙이었다. [의도적 반전: 이 테스트는 과거
    // "정수로_반올림한_인용도_통과한다"로 정반대를 검증했다. 프로젝트 오너의 결정으로 뒤집었다.]
    @Test
    void 소수점_없는_정수_인용은_실측치가_정수일_때만_통과한다() {
        var r = GroundingScorer.score("하루 3개쯤 나갑니다.", snapshot);

        assertThat(r.ungrounded()).contains("3");
    }

    // 실측치가 우연히 정수로 딱 떨어지면(렌더가 "8.0"으로 보여줬을 값) 정수 인용은 표를
    // 그대로 옮긴 것이므로 통과해야 한다. 이 예외가 없으면 일평균이 정수인 상품마다
    // 정상적인 정수 인용을 전부 오탐으로 잡는다.
    @Test
    void 실측치가_정수일_때는_정수_인용도_통과한다() {
        var wholeNumberSnapshot = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 8.0, 15, 4.6875,
                        900L, LocalDate.of(2026, 9, 1), 50)));

        var r = GroundingScorer.score("하루 8개씩 나갑니다.", wholeNumberSnapshot);

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

    // 일평균 3.2333이 정수 3으로 반올림된다고 해서 "발주 #3"이 통과하면 안 된다 —
    // 진짜 발주 번호는 900이다. 예전엔 ID_PHRASE 위치 게이팅으로 막았지만, 지금은 정수
    // 인용이 실측치의 소수 1자리 반올림과 같을 때만 통과하는 규칙 자체가 이 세탁을 막는다
    // (3.2333의 소수 1자리는 3.2 ≠ 정수 3).
    @Test
    void 발주_번호는_실측치_반올림과_충돌해도_잡는다() {
        var r = GroundingScorer.score("발주 #3을 참고하세요.", snapshot);

        assertThat(r.ungrounded()).contains("3");
    }

    // "가용은 5개"처럼 조사가 붙으면 접두어 정규식은 못 걸러도, 정수 인용 규칙 자체가
    // 막아야 한다(소진 예상 4.6875의 소수 1자리는 4.7 ≠ 정수 5). 조사 우회가 실제로
    // 재발했던 사례라 표면형이 아니라 규칙으로 막혔는지 확인하는 회귀 테스트다.
    @Test
    void 조사가_붙어도_실측치_반올림_세탁은_잡는다() {
        var r = GroundingScorer.score("가용은 5개입니다.", snapshot);

        assertThat(r.ungrounded()).contains("5");
    }

    // 창 길이 30일과 상위 5개는 표에 없지만 환각이 아니다. 화이트리스트로 뺀다.
    // 다만 넓히면 진짜 환각도 통과하므로 이 둘만 둔다.
    @Test
    void 창_길이와_목록_개수는_화이트리스트다() {
        var r = GroundingScorer.score("최근 30일 기준 상위 5개 중에서는", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    // 화이트리스트는 "상위 5개" 문구에 고정돼야 한다. 맨 숫자 "5"가 아무 데서나
    // 통과하면 실제 가용 15개인데 "가용 5개"라고 써도 잡지 못한다.
    @Test
    void 화이트리스트_숫자는_문구_밖에서는_통과하지_않는다() {
        var r = GroundingScorer.score("가용 5개입니다.", snapshot);

        assertThat(r.ungrounded()).contains("5");
    }

    @Test
    void 날짜는_표의_날짜와_맞아야_한다() {
        var ok = GroundingScorer.score("9월 1일에 발주했습니다.", snapshot);
        var no = GroundingScorer.score("9월 5일에 발주했습니다.", snapshot);

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).contains("5");
    }

    // 렌더러는 LocalDate를 그대로 이어붙여 ISO 형식("2026-09-08")을 보여주므로 모델이
    // "08"을 그대로 인용해도 정확 집합의 "8"(getMonthValue())과 문자열이 달라 오탐이 났다.
    // 실제 평가 1회차에서 8건의 브리핑에 걸쳐 잡힌 10개의 "환각" 대부분이 이 앞자리 0
    // 날짜였다 — 모델이 화면에 보인 그대로 옮겼을 뿐인데 헤드라인 지표를 부풀렸다.
    @Test
    void 앞자리_0이_붙은_날짜_인용은_통과한다() {
        var lastOrderedOn08 = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 15, 4.6875,
                        900L, LocalDate.of(2026, 9, 8), 50)));

        var r = GroundingScorer.score("2026-09-08에 발주했습니다.", lastOrderedOn08);

        assertThat(r.ungrounded()).isEmpty();
    }

    // 앞자리 0을 지운다고 해서 틀린 날짜까지 통과하면 안 된다 — 모델이 날짜를 지어내는
    // 진짜 환각은 여전히 잡아야 한다.
    @Test
    void 앞자리_0을_지워도_틀린_날짜는_잡는다() {
        var lastOrderedOn08 = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 15, 4.6875,
                        900L, LocalDate.of(2026, 9, 8), 50)));

        var r = GroundingScorer.score("2026-09-05에 발주했습니다.", lastOrderedOn08);

        assertThat(r.ungrounded()).contains("05");
    }

    // 앞자리 0을 지운 값이 다른 필드와 우연히 같아지는 것도 막는다. 가용 8개일 때 "08"은
    // 인용으로 정상이지만, 가용 15개일 때 "08"은 여전히 근거 없는 숫자다.
    @Test
    void 앞자리_0이_붙은_수량도_실제_값과_맞아야_한다() {
        var qty8Snapshot = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 8, 4.6875,
                        900L, LocalDate.of(2026, 9, 1), 50)));

        var ok = GroundingScorer.score("가용 08개", qty8Snapshot);
        var no = GroundingScorer.score("가용 08개", snapshot); // snapshot의 availableQty는 15

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).contains("08");
    }

    // 출고량이 정수 97이어도 모델이 소수점을 붙여 "97.0"이라고 인용할 수 있다.
    // 끝의 0을 떼면 정확 집합의 "97"과 같은 값이므로 통과해야 한다.
    @Test
    void 끝자리_0을_붙인_정수_인용도_통과한다() {
        var r = GroundingScorer.score("이번에 97.0개가 나갔습니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    // "A4용지"의 4처럼 상품명에 박힌 숫자를 인용으로 오독하면 안 된다. 실제 평가셋
    // (pos-01, neg-invented, neg-grounding)의 상품명이라 회귀하면 그 케이스들의 환각률이
    // 전부 체계적으로 부풀어 오른다.
    @Test
    void 상품명에_박힌_숫자는_인용으로_잡지_않는다() {
        var a4Snapshot = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(3L, "A4용지", 240, 30L, 8.0, 12, 1.5,
                        812L, LocalDate.of(2026, 8, 20), 200)));

        var r = GroundingScorer.score("A4용지는 가용이 12개뿐입니다.", a4Snapshot);

        assertThat(r.ungrounded()).isEmpty();
        assertThat(r.total()).isEqualTo(1);
    }

    // 두 차례 평가에서 모델이 지어낸 값이 경과일이었다("직전 발주가 8월 1일이었으므로 이미
    // 40일 이상 경과했으며"). 이제 렌더가 표에 직접 주므로(renderInput의 "(N일 전)") 그
    // 값을 인용하면 환각이 아니라 정상 인용이어야 한다. 다른 필드 값(7·97·30·15·900·50·
    // 8·1·2026·9·10)과 겹치지 않는 40을 골라 exactValues()의 추가 없이는 반드시 잡히게
    // 했다 — exactValues()에서 이 추가를 되돌리면 이 테스트가 실패해야 한다.
    @Test
    void 직전_발주_경과일을_인용하면_통과한다() {
        var eightyOneSnapshot = new BriefingSnapshot(
                LocalDate.of(2026, 9, 10),
                List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 15, 4.6875,
                        900L, LocalDate.of(2026, 8, 1), 50)));

        var r = GroundingScorer.score("직전 발주 이후 이미 40일 이상 경과했습니다.", eightyOneSnapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 숫자가_하나도_없으면_total이_0이고_clean이다() {
        var r = GroundingScorer.score("지금 급한 상품은 없습니다.", snapshot);

        assertThat(r.total()).isZero();
        assertThat(r.clean()).isTrue();
    }
}
