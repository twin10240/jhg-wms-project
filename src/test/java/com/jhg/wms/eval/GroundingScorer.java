package com.jhg.wms.eval;

import com.jhg.wms.domain.BriefingSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 생성문의 숫자를 스냅샷의 값과 대조한다. <b>모델을 부르지 않는다</b> — 확정적이고 공짜다.
 *
 * <p>근거 집합을 둘로 나눈다. 식별자·정수(상품 번호·발주 번호·수량·날짜)는 <b>정확히</b>
 * 일치해야 하고, 실측치(일평균·소진 예상)만 반올림을 인정한다. 하나로 합치면 일평균 3.2333이
 * 정수 3으로 반올림되면서 "발주 #3" 같은 환각까지 통과한다.
 *
 * <p>반올림 규칙: 인용된 숫자의 소수 자릿수에 맞춰 근거값을 반올림해 비교한다.
 * 3.2333은 "3.23"·"3.2"·"3" 모두 정상 인용이고 "3.4"는 아니다.
 */
public final class GroundingScorer {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * "9월 5일" 같은 날짜 표기의 월·일 숫자를 잡아낸다. 이 숫자들은 화이트리스트("5")나
     * 반올림된 실측치(4.6875 → 5)와 우연히 겹칠 수 있으므로, 날짜 표기 안에서는 정확한
     * 날짜 집합({@link #exactValues})하고만 대조한다 — 그렇지 않으면 "9월 5일" 같은
     * 조작된 날짜가 소진 예상 반올림값과 우연히 맞아떨어져 통과해 버린다.
     */
    private static final Pattern DATE_PHRASE = Pattern.compile("(\\d+)월\\s*(\\d+)일");

    /**
     * 표에 없지만 환각이 아닌 값. <b>넓히면 진짜 환각도 통과하므로 늘리지 않는다.</b>
     * 30은 근거 패널의 창 길이(WINDOW_DAYS), 5는 브리핑이 받는 상품 수(TOP_N)다.
     */
    private static final Set<String> WHITELIST = Set.of("30", "5");

    private GroundingScorer() {}

    /**
     * @param total 생성문에 나온 숫자 개수
     * @param ungrounded 근거 집합에 없는 숫자들(원문 표기 그대로)
     */
    public record Result(int total, List<String> ungrounded) {
        public int grounded() { return total - ungrounded.size(); }
        /** 환각이 하나도 없는가. 문단 하나에 틀린 숫자 하나면 그 문단은 못 쓴다. */
        public boolean clean() { return ungrounded.isEmpty(); }
    }

    public static Result score(String body, BriefingSnapshot snapshot) {
        Set<String> exact = exactValues(snapshot);
        List<Double> measures = measureValues(snapshot);
        Set<Integer> datePositions = datePositions(body);

        List<String> ungrounded = new ArrayList<>();
        int total = 0;
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            String token = m.group();
            total++;
            boolean dateToken = datePositions.contains(m.start());
            if (!grounded(token, exact, measures, dateToken)) ungrounded.add(token);
        }
        return new Result(total, ungrounded);
    }

    /** 날짜 표기("9월 5일")의 월·일 숫자가 시작하는 본문 위치. */
    private static Set<Integer> datePositions(String body) {
        Set<Integer> positions = new java.util.HashSet<>();
        Matcher dm = DATE_PHRASE.matcher(body);
        while (dm.find()) {
            positions.add(dm.start(1));
            positions.add(dm.start(2));
        }
        return positions;
    }

    private static boolean grounded(String token, Set<String> exact, List<Double> measures, boolean dateToken) {
        if (exact.contains(token)) return true;
        if (exact.contains(stripTrailingZeros(token))) return true;
        if (dateToken) return false; // 날짜 숫자는 화이트리스트·실측치 반올림과 우연히 겹쳐도 인정하지 않는다.
        if (WHITELIST.contains(token)) return true;

        int decimals = token.contains(".") ? token.length() - token.indexOf('.') - 1 : 0;
        BigDecimal quoted = new BigDecimal(token);
        for (double v : measures) {
            if (BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP)
                    .compareTo(quoted) == 0) return true;
        }
        return false;
    }

    /** 반올림을 인정하지 않는 값들. 문자열로 담아 정확 일치만 본다. */
    private static Set<String> exactValues(BriefingSnapshot snapshot) {
        Set<String> s = new LinkedHashSet<>();
        for (BriefingSnapshot.Row r : snapshot.rows()) {
            s.add(String.valueOf(r.productId()));
            s.add(String.valueOf(r.shippedQty()));
            s.add(String.valueOf(r.sampleDays()));
            s.add(String.valueOf(r.availableQty()));
            if (r.lastOrderId() != null) s.add(String.valueOf(r.lastOrderId()));
            if (r.lastOrderQty() != null) s.add(String.valueOf(r.lastOrderQty()));
            if (r.lastOrderedOn() != null) {
                s.add(String.valueOf(r.lastOrderedOn().getYear()));
                s.add(String.valueOf(r.lastOrderedOn().getMonthValue()));
                s.add(String.valueOf(r.lastOrderedOn().getDayOfMonth()));
            }
        }
        s.add(String.valueOf(snapshot.generatedOn().getYear()));
        s.add(String.valueOf(snapshot.generatedOn().getMonthValue()));
        s.add(String.valueOf(snapshot.generatedOn().getDayOfMonth()));
        return s;
    }

    /** 반올림을 인정하는 실측치. */
    private static List<Double> measureValues(BriefingSnapshot snapshot) {
        List<Double> v = new ArrayList<>();
        for (BriefingSnapshot.Row r : snapshot.rows()) {
            v.add(r.dailyAverage());
            if (r.daysToStockout() != null) v.add(r.daysToStockout());
        }
        return v;
    }

    private static String stripTrailingZeros(String token) {
        if (!token.contains(".")) return token;
        return new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
}
