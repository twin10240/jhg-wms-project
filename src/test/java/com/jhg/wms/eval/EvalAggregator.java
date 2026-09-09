package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.RmaDisposition;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 순수 집계. API도 파일도 모른다 — 그래서 공짜로 검증된다. */
public final class EvalAggregator {

    private EvalAggregator() {}

    public record CaseResult(EvalCase source,
                             String majority,
                             boolean unstable,
                             List<EvalObservation> observations) {}

    /** perCategory의 int[]는 {맞은 수, 전체 수}다. */
    public record Summary(int total,
                          int correct,
                          Map<String, int[]> perCategory,
                          int unstableCount,
                          Map<String, Map<RmaDisposition, Integer>> dispositionByCategory,
                          Map<Confidence, Integer> confidenceDistribution,
                          Map<Confidence, Integer> confidenceOfUnstable,
                          int failedObservations,
                          long inputTokens,
                          long outputTokens) {}

    public static CaseResult toCaseResult(EvalCase source, List<EvalObservation> observations) {
        Map<String, Integer> 표 = new LinkedHashMap<>();
        for (EvalObservation o : observations)
            if (o.succeeded()) 표.merge(o.category(), 1, Integer::sum);

        int 최다 = 표.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // 최빈값이 여럿이면 판단 불가다. 임의로 하나를 고르면 정확도가 우연에 좌우된다.
        List<String> 최빈 = 표.entrySet().stream()
                .filter(e -> e.getValue() == 최다).map(Map.Entry::getKey).toList();
        String majority = 최빈.size() == 1 ? 최빈.get(0) : null;

        boolean unstable = 표.size() > 1;
        return new CaseResult(source, majority, unstable, observations);
    }

    /**
     * @param categories 이 평가셋의 범주 전체. 0건인 범주도 표에 자리를 갖게 하려고 받는다 —
     *                   "라벨이 하나도 없는 범주"와 "0건인 범주"는 다른 뜻이고, 라벨에 등장한
     *                   것만 세면 그 구분이 사라진다.
     */
    public static Summary summarize(List<CaseResult> results, List<String> categories) {
        int correct = 0, unstableCount = 0, failed = 0;
        long in = 0, out = 0;
        Map<String, int[]> perCategory = new LinkedHashMap<>();
        for (String c : categories) perCategory.put(c, new int[2]);
        Map<String, Map<RmaDisposition, Integer>> byCategory = new LinkedHashMap<>();
        Map<Confidence, Integer> confAll = new EnumMap<>(Confidence.class);
        Map<Confidence, Integer> confUnstable = new EnumMap<>(Confidence.class);

        for (CaseResult r : results) {
            String expected = r.source().expectedCategory();
            int[] 칸 = perCategory.computeIfAbsent(expected, k -> new int[2]);
            칸[1]++;
            if (expected.equals(r.majority())) { 칸[0]++; correct++; }
            if (r.unstable()) unstableCount++;

            for (EvalObservation o : r.observations()) {
                if (!o.succeeded()) { failed++; continue; }
                in += o.inputTokens();
                out += o.outputTokens();
                byCategory.computeIfAbsent(o.category(), k -> new LinkedHashMap<>())
                        .merge(o.disposition(), 1, Integer::sum);
                confAll.merge(o.confidence(), 1, Integer::sum);
                if (r.unstable()) confUnstable.merge(o.confidence(), 1, Integer::sum);
            }
        }
        return new Summary(results.size(), correct, perCategory, unstableCount,
                byCategory, confAll, confUnstable, failed, in, out);
    }
}
