package com.jhg.wms.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingReportWriterTest {

    private BriefingReportWriter.CaseScore score(String id, boolean negative,
                                                 int total, int ungrounded,
                                                 Map<String, String> itemMajority,
                                                 List<String> unstableItems) {
        return new BriefingReportWriter.CaseScore(id, negative, total, ungrounded,
                itemMajority, unstableItems, "판정 근거 없음");
    }

    // 네거티브 절이 맨 위다. judge가 망가진 브리핑을 통과시키면 아래 표는 읽을 가치가 없다.
    @Test
    void 네거티브_절이_사실성_절보다_먼저_나온다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("neg-reasoned", true, 0, 0, Map.of("REASONED", "NO"), List.of())));

        assertThat(report.indexOf("## 네거티브")).isLessThan(report.indexOf("## 사실성"));
    }

    @Test
    void 환각이_있는_브리핑_수를_센다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("pos-01", false, 10, 2, Map.of("REASONED", "YES"), List.of()),
                score("pos-02", false, 8, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).contains("환각 있는 브리핑 **1/2**");
    }

    // 종합 점수를 만들지 않는다. 만들면 어느 쪽이 나빠서 떨어졌는지 못 읽는다.
    @Test
    void 종합_점수를_내지_않는다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("pos-01", false, 10, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).doesNotContain("종합");
    }
}
