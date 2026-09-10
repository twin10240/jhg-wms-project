package com.jhg.wms.eval;

import java.time.LocalDate;
import java.util.ArrayList;
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

    /** Haiku 4.5 단가($1 / $5 per MTok)와 환율 1,400원 기준. 자릿수 감을 잡기 위한 값이다. */
    private static final double USD_PER_INPUT_TOKEN = 1.0 / 1_000_000;
    private static final double USD_PER_OUTPUT_TOKEN = 5.0 / 1_000_000;
    private static final double KRW_PER_USD = 1400;

    private BriefingReportWriter() {}

    /**
     * @param itemMajority 항목명 → 다수결("YES"/"NO"/null). 네거티브는 지정 항목 하나만 담긴다.
     * @param unstableItems 3회가 갈린 항목들. judge가 흔들린다는 신호다.
     * @param ungroundedTokens 근거 집합에 없는 숫자들(원문 표기). 개수만으로는 "어떤" 숫자가
     *        환각인지 알 수 없어 원인을 못 고친다 — 토큰 자체를 들고 다닌다.
     * @param note 생성 실패 등 이 케이스를 해석하는 데 필요한 한 줄. 비어 있지 않으면 리포트에 남는다.
     * @param body 생성된 브리핑 전문. 생성이 실패하면 null이다. 실패한 케이스만 리포트 끝에 원문(또는
     *        본문이 없으면 note)으로 남긴다.
     */
    public record CaseScore(String id, boolean negative, int numbersTotal, List<String> ungroundedTokens,
                            Map<String, String> itemMajority, List<String> unstableItems, String note,
                            String body) {
        public int numbersUngrounded() { return ungroundedTokens.size(); }
    }

    public static String render(String generatorModel, String judgeModel, int repeats, List<CaseScore> scores,
                                int failedGenerations, int failedJudgeCalls,
                                long inputTokens, long outputTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 발주 브리핑 평가 — ").append(LocalDate.now()).append("\n\n");
        sb.append("- 모델(생성): `").append(generatorModel).append("`\n");
        sb.append("- 모델(판정): `").append(judgeModel).append("`\n");
        sb.append("- 반복: judge ").append(repeats).append("회\n");
        // 생성/판정이 조용히 비어도 숫자가 정상처럼 보이던 문제 — 실패는 여기서 먼저 센다.
        if (failedGenerations > 0)
            sb.append("- 생성 실패 **").append(failedGenerations).append("건**\n");
        if (failedJudgeCalls > 0)
            sb.append("- 판정 실패 관측 **").append(failedJudgeCalls).append("회**\n");
        sb.append("\n");

        // ── 네거티브가 먼저다 ──
        List<CaseScore> negatives = scores.stream().filter(CaseScore::negative).toList();
        sb.append("## 네거티브 케이스 — judge가 떨어뜨렸는가\n\n");
        sb.append("여기가 통과 못 하면 아래 두 표를 믿지 않는다.\n\n");
        sb.append("| id | 떨어져야 할 항목 | 다수결 | 판정 |\n|---|---|---|---|\n");
        for (CaseScore c : negatives) {
            String item = c.itemMajority().keySet().stream().findFirst().orElse("-");
            String majority = c.itemMajority().values().stream().findFirst().orElse("-");
            boolean caught = "NO".equals(majority);
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
        sb.append("| id | 숫자 | 근거 없음 | 근거 없는 숫자 |\n|---|---|---|---|\n");
        for (CaseScore c : positives) {
            sb.append("| `").append(c.id()).append("` | ").append(c.numbersTotal())
              .append(" | ").append(c.numbersUngrounded()).append(" | ")
              .append(c.ungroundedTokens().isEmpty() ? "-" : String.join(", ", c.ungroundedTokens()))
              .append(" |\n");
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

        // ── 실패한 브리핑 원문 ──
        // 표는 판정만 보여준다. 그 숫자가 진짜 모델의 환각인지 채점기의 오탐인지, 모델이
        // 실제로 뭐라고 썼는지는 원문 없이는 알 수 없다. 통과한 케이스까지 다 실으면
        // 실패가 묻히므로 실패한 것만 남긴다.
        sb.append("\n## 실패한 브리핑 원문\n\n");
        List<CaseScore> failed = positives.stream().filter(BriefingReportWriter::failed).toList();
        if (failed.isEmpty()) {
            sb.append("실패한 케이스 없음.\n");
        } else {
            for (CaseScore c : failed) {
                sb.append("### `").append(c.id()).append("` — ").append(failureReason(c)).append("\n\n");
                // 생성이 실패하면 원문이 없다 — note("생성 실패")를 대신 남겨 표에서 사라지지 않게 한다.
                sb.append(c.body() != null ? c.body() : c.note()).append("\n\n");
            }
        }

        double 원 = (inputTokens * USD_PER_INPUT_TOKEN + outputTokens * USD_PER_OUTPUT_TOKEN) * KRW_PER_USD;
        sb.append("\n## 누적 토큰\n\n");
        sb.append(String.format("입력 %,d · 출력 %,d — 약 %.0f원%n", inputTokens, outputTokens, 원));

        return sb.toString();
    }

    private static boolean failed(CaseScore c) {
        return c.body() == null || !c.ungroundedTokens().isEmpty() || c.itemMajority().containsValue("NO");
    }

    private static String failureReason(CaseScore c) {
        List<String> reasons = new ArrayList<>();
        if (c.body() == null)
            reasons.add(c.note().isEmpty() ? "생성 실패" : c.note());
        if (!c.ungroundedTokens().isEmpty())
            reasons.add("근거 없음: " + String.join(", ", c.ungroundedTokens()));
        List<String> failedItems = c.itemMajority().entrySet().stream()
                .filter(e -> "NO".equals(e.getValue())).map(Map.Entry::getKey).toList();
        if (!failedItems.isEmpty())
            reasons.add("판정 실패: " + String.join(", ", failedItems));
        return String.join(" / ", reasons);
    }
}
