package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** 집계 결과를 사람이 읽고 문서로 옮길 수 있는 마크다운으로 만든다. */
public final class EvalReportWriter {

    /** Haiku 4.5 단가($1 / $5 per MTok)와 환율 1,400원 기준. 자릿수 감을 잡기 위한 값이다. */
    private static final double USD_PER_INPUT_TOKEN = 1.0 / 1_000_000;
    private static final double USD_PER_OUTPUT_TOKEN = 5.0 / 1_000_000;
    private static final double KRW_PER_USD = 1400;

    private EvalReportWriter() {}

    public static String render(String model, int repeats,
                                List<EvalAggregator.CaseResult> results,
                                EvalAggregator.Summary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 분류 품질 평가 — ").append(LocalDate.now()).append("\n\n");
        sb.append("- 모델: `").append(model).append("`\n");
        sb.append("- 규모: ").append(summary.total()).append("건 × ").append(repeats).append("회\n");
        sb.append("- 라벨: Claude 초안, 사람 검수 — 최종 권위는 사람에게 있다\n\n");

        double 정확도 = summary.total() == 0 ? 0 : 100.0 * summary.correct() / summary.total();
        sb.append("## 정확도\n\n");
        sb.append(String.format("다수결 정확도 **%d/%d (%.1f%%)**%n", summary.correct(), summary.total(), 정확도));
        sb.append("흔들린 케이스 **").append(summary.unstableCount()).append("건**\n");
        if (summary.failedObservations() > 0)
            sb.append("분류 실패 관측 **").append(summary.failedObservations()).append("회**\n");
        sb.append("\n| 범주 | 맞음 / 전체 |\n|---|---|\n");
        for (ReturnCategory c : ReturnCategory.values()) {
            int[] 칸 = summary.perCategory().get(c);
            if (칸 != null) sb.append("| `").append(c).append("` | ").append(칸[0]).append(" / ").append(칸[1]).append(" |\n");
        }

        sb.append("\n## 틀리거나 흔들린 케이스\n\n");
        sb.append("| id | 기대 | 다수결 | 흔들림 | note |\n|---|---|---|---|---|\n");
        for (EvalAggregator.CaseResult r : results) {
            boolean 틀림 = !r.source().expectedCategory().equals(r.majority());
            if (!틀림 && !r.unstable()) continue;
            sb.append("| `").append(r.source().id()).append("` | `").append(r.source().expectedCategory())
              .append("` | ").append(r.majority() == null ? "판단 불가" : "`" + r.majority() + "`")
              .append(" | ").append(r.unstable() ? "예" : "아니오")
              .append(" | ").append(r.source().note()).append(" |\n");
        }

        // 1회차(2026-09-01)가 여기서 막혔다. 케이스 단위 요약만으로는 (1) 한 케이스 안에서 처분이
        // 갈렸는지, (2) 틀린 케이스가 어떤 신뢰도를 받았는지 알 수 없어 산술로 추론하는 수밖에 없었다.
        // 관측을 하나씩 적으면 다음 회차에서 같은 자리에 다시 막히지 않는다.
        sb.append("\n## 케이스별 관측\n\n");
        sb.append("| id | 기대 | 다수결 | 관측 (범주 · 처분 · 신뢰도) |\n|---|---|---|---|\n");
        for (EvalAggregator.CaseResult r : results) {
            boolean 맞음 = r.source().expectedCategory().equals(r.majority());
            sb.append("| `").append(r.source().id()).append("` | `").append(r.source().expectedCategory())
              .append("` | ").append(r.majority() == null ? "판단 불가" : "`" + r.majority() + "`")
              .append(맞음 ? " ✓" : " ✗").append(" | ");
            StringJoiner 관측 = new StringJoiner(" / ");
            for (EvalObservation o : r.observations())
                관측.add(o.succeeded()
                        ? "`" + o.category() + "`·`" + o.disposition() + "`·`" + o.confidence() + "`"
                        : "실패");   // 자리를 비우면 3회 중 몇 번이 실패인지가 표에서 사라진다
            sb.append(관측).append(" |\n");
        }

        sb.append("\n## 처분 매핑\n\n");
        sb.append("같은 범주에 항상 같은 처분이 붙으면 매핑 테이블로 대체할 수 있다.\n\n");
        sb.append("| 범주 | 처분 분포 |\n|---|---|\n");
        for (var e : summary.dispositionByCategory().entrySet()) {
            sb.append("| `").append(e.getKey()).append("` | ");
            for (Map.Entry<RmaDisposition, Integer> d : e.getValue().entrySet())
                sb.append("`").append(d.getKey()).append("` ").append(d.getValue()).append(" · ");
            sb.setLength(sb.length() - 3);
            sb.append(" |\n");
        }

        sb.append("\n## 신뢰도\n\n| 신뢰도 | 전체 | 흔들린 케이스 |\n|---|---|---|\n");
        for (Confidence c : Confidence.values())
            sb.append("| `").append(c).append("` | ")
              .append(summary.confidenceDistribution().getOrDefault(c, 0)).append(" | ")
              .append(summary.confidenceOfUnstable().getOrDefault(c, 0)).append(" |\n");

        double 원 = (summary.inputTokens() * USD_PER_INPUT_TOKEN
                + summary.outputTokens() * USD_PER_OUTPUT_TOKEN) * KRW_PER_USD;
        sb.append("\n## 누적 토큰\n\n");
        sb.append(String.format("입력 %,d · 출력 %,d — 약 %.0f원%n",
                summary.inputTokens(), summary.outputTokens(), 원));
        return sb.toString();
    }
}
