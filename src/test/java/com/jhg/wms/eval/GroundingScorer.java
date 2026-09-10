package com.jhg.wms.eval;

import com.jhg.wms.domain.BriefingSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 생성문의 숫자를 스냅샷의 값과 대조한다. <b>모델을 부르지 않는다</b> — 확정적이고 공짜다.
 *
 * <p>근거 집합은 둘이다. 식별자·수량(상품 번호·발주 번호·가용)과 날짜는 <b>정확히</b> 일치해야
 * 하고, 실측치(일평균·소진 예상)만 반올림을 인정한다. 하지만 이 구분은 숫자 문자열만으로는
 * 지킬 수 없다 — 값이 아니라 <b>문장 속 위치</b>가 자리를 정한다. "상품 #7", "발주 #900",
 * "가용 15개"처럼 렌더러가 실제로 쓰는 접두어({@link #ID_PHRASE}) 바로 뒤, 그리고
 * "9월 5일"의 월·일 자리({@link #DATE_PHRASE})는 정확 집합하고만 대조하고, 반올림·화이트리스트
 * 경로를 아예 타지 않는다. 이 게이팅이 없으면 일평균 3.2333이 정수 3으로 반올림되면서
 * "발주 #3" 같은 환각이 통과하고, 소진 예상 4.6875가 5로 반올림되면서 "가용 5개"(실제 15개)
 * 같은 환각도 통과한다 — 둘 다 실측치 반올림 경로가 위치와 무관하게 모든 숫자를 대상으로 돌기
 * 때문이다.
 *
 * <p>화이트리스트(창 길이 30일, 상위 5개)도 같은 이유로 문구에 고정했다({@link #WINDOW_PHRASE},
 * {@link #TOPN_PHRASE}). "최근 30일"·"상위 5개" 자리에서만 인정하고, 맨 숫자 "5"는 더 이상
 * 아무 데서나 통과하지 않는다.
 *
 * <p><b>남은 구멍:</b> "최근 출고"(shippedQty)와 "표본"(sampleDays)은 접두어로 게이팅하지
 * 않았다 — 이 스냅샷에서는 실측치 반올림값(3, 5, 3.2, 4.7)과 겹치지 않아 테스트가 못 잡는다.
 * 값이 겹치는 스냅샷이 오면 "최근 출고 5개"(실제 다른 값) 같은 환각이 통과할 수 있다. 넓히려면
 * ID_PHRASE에 같은 방식으로 추가하면 된다.
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
     * 렌더러({@code ClaudePurchaseOrderBriefingGenerator.renderInput})가 실제로 내보내는
     * 식별자·수량 접두어. "#"은 상품 번호·발주 번호 모두에 렌더러가 직접 붙이는 표기라 가장
     * 흔하고, "발주"·"상품"·"가용"은 모델이 접두어를 살려 인용할 때의 문구다. 이 접두어 바로
     * 뒤의 숫자는 정확 집합하고만 대조한다 — 화이트리스트도, 실측치 반올림도 타지 않는다.
     * 그렇지 않으면 "발주 #3"이 일평균 3.2333의 반올림과, "가용 #5"가 소진 예상 4.6875의
     * 반올림과 우연히 맞아떨어져 통과해 버린다.
     */
    private static final Pattern ID_PHRASE =
            Pattern.compile("(?:#|발주\\s*#?|상품\\s*#?|가용\\s*:?\\s*)(\\d+)");

    /** "최근 30일"의 창 길이(WINDOW_DAYS). 이 문구 안의 숫자만 화이트리스트로 인정한다. */
    private static final Pattern WINDOW_PHRASE = Pattern.compile("최근\\s*(\\d+)일");

    /** "상위 5개"의 상품 수(TOP_N). 이 문구 안의 숫자만 화이트리스트로 인정한다. */
    private static final Pattern TOPN_PHRASE = Pattern.compile("상위\\s*(\\d+)개");

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
        Set<Integer> exactOnlyPositions = exactOnlyPositions(body);
        Set<Integer> whitelistPositions = whitelistPositions(body);

        List<String> ungrounded = new ArrayList<>();
        int total = 0;
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            String token = m.group();
            total++;
            boolean exactOnly = exactOnlyPositions.contains(m.start());
            boolean whitelisted = whitelistPositions.contains(m.start());
            if (!grounded(token, exact, measures, exactOnly, whitelisted)) ungrounded.add(token);
        }
        return new Result(total, ungrounded);
    }

    /**
     * 정확 집합하고만 대조해야 하는 자리: 날짜 표기("9월 5일")의 월·일 숫자, 그리고
     * 식별자·수량 접두어({@link #ID_PHRASE}) 바로 뒤의 숫자.
     */
    private static Set<Integer> exactOnlyPositions(String body) {
        Set<Integer> positions = new HashSet<>();
        Matcher dm = DATE_PHRASE.matcher(body);
        while (dm.find()) {
            positions.add(dm.start(1));
            positions.add(dm.start(2));
        }
        Matcher im = ID_PHRASE.matcher(body);
        while (im.find()) {
            positions.add(im.start(1));
        }
        return positions;
    }

    /** 화이트리스트 문구("최근 30일", "상위 5개") 안의 숫자가 시작하는 본문 위치. */
    private static Set<Integer> whitelistPositions(String body) {
        Set<Integer> positions = new HashSet<>();
        Matcher wm = WINDOW_PHRASE.matcher(body);
        while (wm.find()) {
            positions.add(wm.start(1));
        }
        Matcher tm = TOPN_PHRASE.matcher(body);
        while (tm.find()) {
            positions.add(tm.start(1));
        }
        return positions;
    }

    private static boolean grounded(String token, Set<String> exact, List<Double> measures,
                                     boolean exactOnly, boolean whitelisted) {
        if (exact.contains(token)) return true;
        if (exact.contains(stripTrailingZeros(token))) return true;
        // 식별자·수량·날짜 자리는 화이트리스트·실측치 반올림과 우연히 겹쳐도 인정하지 않는다.
        if (exactOnly) return false;
        if (whitelisted) return true;

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
