package com.jhg.wms.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingReportWriterTest {

    // 기존 호출부는 "근거 없는 숫자 개수"만 알면 됐다 — 토큰 값은 몰라도 되니 자리표시 토큰으로 채운다.
    private BriefingReportWriter.CaseScore score(String id, boolean negative,
                                                 int total, int ungrounded,
                                                 Map<String, String> itemMajority,
                                                 List<String> unstableItems) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < ungrounded; i++) tokens.add("tok" + i);
        return score(id, negative, total, tokens, itemMajority, unstableItems, "");
    }

    private BriefingReportWriter.CaseScore score(String id, boolean negative,
                                                 int total, List<String> ungroundedTokens,
                                                 Map<String, String> itemMajority,
                                                 List<String> unstableItems, String body) {
        return new BriefingReportWriter.CaseScore(id, negative, total, ungroundedTokens,
                itemMajority, unstableItems, "판정 근거 없음", body);
    }

    private BriefingReportWriter.CaseScore failedGeneration(String id) {
        return new BriefingReportWriter.CaseScore(id, false, 0, List.of(), Map.of(), List.of(), "생성 실패", null);
    }

    // 실패/토큰 카운터를 안 쓰는 기존 테스트는 전부 0으로 채운다.
    private String render(List<BriefingReportWriter.CaseScore> scores) {
        return BriefingReportWriter.render("claude-haiku-4-5", "claude-haiku-4-5", 3, scores, 0, 0, 0, 0);
    }

    // 네거티브 절이 맨 위다. judge가 망가진 브리핑을 통과시키면 아래 표는 읽을 가치가 없다.
    @Test
    void 네거티브_절이_사실성_절보다_먼저_나온다() {
        String report = render(List.of(
                score("neg-reasoned", true, 0, 0, Map.of("REASONED", "NO"), List.of())));

        assertThat(report.indexOf("## 네거티브")).isLessThan(report.indexOf("## 사실성"));
    }

    @Test
    void 환각이_있는_브리핑_수를_센다() {
        String report = render(List.of(
                score("pos-01", false, 10, 2, Map.of("REASONED", "YES"), List.of()),
                score("pos-02", false, 8, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).contains("환각 있는 브리핑 **1/2**");
    }

    // 종합 점수를 만들지 않는다. 만들면 어느 쪽이 나빠서 떨어졌는지 못 읽는다.
    @Test
    void 종합_점수를_내지_않는다() {
        String report = render(List.of(
                score("pos-01", false, 10, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).doesNotContain("종합");
    }

    // 개수만으로는 어떤 숫자가 근거 없는지 못 읽는다 — 사실성 표에 토큰 자체가 나와야 한다.
    @Test
    void 사실성_표에_근거_없는_토큰이_나온다() {
        String report = render(List.of(
                score("pos-06", false, 16, List.of("5.2", "40", "3"),
                        Map.of("REASONED", "YES"), List.of(), "본문")));

        assertThat(report).contains("`pos-06` | 16 | 3 | 5.2, 40, 3");
    }

    @Test
    void 실패한_케이스의_원문이_실패한_브리핑_원문_절에_나온다() {
        String report = render(List.of(
                score("pos-06", false, 16, List.of("5.2", "40", "3"),
                        Map.of("REASONED", "YES"), List.of(), "가용 재고는 5.2개이며 40개를 발주했다.")));

        int section = report.indexOf("## 실패한 브리핑 원문");
        assertThat(section).isGreaterThan(-1);
        assertThat(report.substring(section)).contains("pos-06")
                .contains("가용 재고는 5.2개이며 40개를 발주했다.");
    }

    @Test
    void 판정_실패한_케이스의_원문도_나온다() {
        String report = render(List.of(
                score("pos-07", false, 5, List.of(),
                        Map.of("REASONED", "NO"), List.of(), "이 상품은 재고가 충분하다.")));

        int section = report.indexOf("## 실패한 브리핑 원문");
        assertThat(report.substring(section)).contains("pos-07")
                .contains("이 상품은 재고가 충분하다.");
    }

    @Test
    void 모두_통과한_케이스의_원문은_나오지_않는다() {
        String report = render(List.of(
                score("pos-08", false, 5, List.of(),
                        Map.of("REASONED", "YES"), List.of(), "완벽하게 작성된 브리핑 본문.")));

        int section = report.indexOf("## 실패한 브리핑 원문");
        assertThat(section).isGreaterThan(-1);
        assertThat(report.substring(section)).doesNotContain("완벽하게 작성된 브리핑 본문.");
    }

    // 생성이 실패하면 numbersTotal=0/ungrounded=0이라 표만 보면 완벽한 브리핑처럼 보인다.
    // 실패한 브리핑 원문 절에 note로라도 나와야 조용히 "깨끗함"으로 묻히지 않는다.
    @Test
    void 생성_실패한_케이스가_실패한_브리핑_원문에_나온다() {
        String report = render(List.of(failedGeneration("pos-09")));

        int section = report.indexOf("## 실패한 브리핑 원문");
        assertThat(section).isGreaterThan(-1);
        assertThat(report.substring(section)).contains("pos-09").contains("생성 실패");
    }

    @Test
    void 헤더에_생성_실패_건수와_판정_실패_횟수가_보인다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5-20251001", "claude-haiku-4-5-20251001",
                3, List.of(failedGeneration("pos-09")), 1, 2, 0, 0);

        assertThat(report).contains("생성 실패 **1건**").contains("판정 실패 관측 **2회**");
    }

    // 실패가 없으면 카운터 줄 자체를 안 낸다 — 0건인 항목까지 나열하면 늘 있는 잡음이 된다.
    @Test
    void 실패가_없으면_실패_카운터_줄이_없다() {
        String report = render(List.of(
                score("pos-01", false, 10, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).doesNotContain("생성 실패 **").doesNotContain("판정 실패 관측 **");
    }

    @Test
    void 생성_모델과_판정_모델을_따로_보여준다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5-20251001", "claude-haiku-4-5-20250915",
                3, List.of(), 0, 0, 0, 0);

        assertThat(report).contains("모델(생성): `claude-haiku-4-5-20251001`")
                .contains("모델(판정): `claude-haiku-4-5-20250915`");
    }

    @Test
    void 누적_토큰_절이_생성과_판정을_합쳐_보여준다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", "claude-haiku-4-5",
                3, List.of(), 0, 0, 1000, 2000);

        int section = report.indexOf("## 누적 토큰");
        assertThat(section).isGreaterThan(-1);
        assertThat(report.substring(section)).contains("입력 1,000").contains("출력 2,000");
    }
}
