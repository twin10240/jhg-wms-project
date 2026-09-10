package com.jhg.wms.eval;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 두 채점의 집계 축이 다르다(비율 vs 항목별 통과율). <b>한 숫자로 합치지 않는다</b> —
 * "종합 4.2" 같은 값을 만들면 어느 쪽이 나빠서 떨어졌는지 읽을 수 없다.
 *
 * <p>네거티브 절이 맨 위다. judge가 망가진 브리핑을 통과시키면 아래 두 표는 읽을 가치가 없다.
 * 순서 자체가 "judge를 먼저 검증한다"는 규율의 표현이다.
 */
public final class BriefingReportWriter {

    private BriefingReportWriter() {}

    /**
     * @param itemMajority 항목명 → 다수결("YES"/"NO"/null). 네거티브는 지정 항목 하나만 담긴다.
     * @param unstableItems 3회가 갈린 항목들. judge가 흔들린다는 신호다.
     */
    public record CaseScore(String id, boolean negative, int numbersTotal, int numbersUngrounded,
                            Map<String, String> itemMajority, List<String> unstableItems, String note) {}

    public static String render(String model, int repeats, List<CaseScore> scores) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 발주 브리핑 평가 — ").append(LocalDate.now()).append("\n\n");
        sb.append("- 모델: `").append(model).append("`\n");
        sb.append("- 반복: judge ").append(repeats).append("회\n\n");

        // ── 네거티브가 먼저다 ──
        List<CaseScore> negatives = scores.stream().filter(CaseScore::negative).toList();
        sb.append("## 네거티브 케이스 — judge가 떨어뜨렸는가\n\n");
        sb.append("여기가 통과 못 하면 아래 두 표를 믿지 않는다.\n\n");
        sb.append("| id | 떨어져야 할 항목 | 다수결 | 판정 |\n|---|---|---|---|\n");
        for (CaseScore c : negatives) {
            String item = c.itemMajority().keySet().stream().findFirst().orElse("-");
            String majority = c.itemMajority().values().stream().findFirst().orElse("-");
            boolean caught = "NO".equals(majority) || "GROUNDING".equals(item) && c.numbersUngrounded() > 0;
            sb.append("| `").append(c.id()).append("` | `").append(item).append("` | ")
              .append(majority).append(" | ").append(caught ? "잡았다" : "**놓쳤다**").append(" |\n");
        }

        // ── 사실성 ──
        List<CaseScore> positives = scores.stream().filter(c -> !c.negative()).toList();
        int totalNums = positives.stream().mapToInt(CaseScore::numbersTotal).sum();
        int ungrounded = positives.stream().mapToInt(CaseScore::numbersUngrounded).sum();
        long dirty = positives.stream().filter(c -> c.numbersUngrounded() > 0).count();

        sb.append("\n## 사실성\n\n");
        sb.append("근거 없는 숫자 **").append(ungrounded).append("/").append(totalNums).append("**\n");
        sb.append("환각 있는 브리핑 **").append(dirty).append("/").append(positives.size()).append("**\n\n");
        sb.append("| id | 숫자 | 근거 없음 |\n|---|---|---|\n");
        for (CaseScore c : positives) {
            sb.append("| `").append(c.id()).append("` | ").append(c.numbersTotal())
              .append(" | ").append(c.numbersUngrounded()).append(" |\n");
        }

        // ── 유용성 ──
        sb.append("\n## 유용성\n\n");
        sb.append("| 항목 | 통과 | 흔들림 |\n|---|---|---|\n");
        for (String item : BriefingJudge.ITEMS) {
            long pass = positives.stream().filter(c -> "YES".equals(c.itemMajority().get(item))).count();
            long unstable = positives.stream().filter(c -> c.unstableItems().contains(item)).count();
            sb.append("| `").append(item).append("` | ").append(pass).append("/")
              .append(positives.size()).append(" | ").append(unstable).append(" |\n");
        }

        return sb.toString();
    }
}
