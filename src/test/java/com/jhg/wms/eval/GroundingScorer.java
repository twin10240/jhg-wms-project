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
 * <p>세 차례의 리뷰가 같은 버그를 세 번 잡았다: 어떤 자리의 숫자가 <i>다른</i> 필드의 값을
 * 반올림한 것과 우연히 같아지면서 세탁됐다. 처음엔 "발주 #3"(진짜 발주 900, 일평균 3.2333이
 * 정수로 반올림되면 3), 다음엔 "9월 5일" 같은 조작된 날짜, 이번엔 조사가 붙은 "가용은 5개"
 * (진짜 가용 15, 소진 예상 4.6875가 반올림되면 5)가 어떤 접두어 정규식에도 안 걸려서 또
 * 통과했다. 매번 위치를 잡는 정규식(DATE_PHRASE, ID_PHRASE)을 하나 더 추가하는 패치였고,
 * 매번 다른 표면형이 그 정규식을 피해갔다 — 문제는 표면형이 아니라 <b>실측치를 정수로
 * 반올림한 값을 통째로 인정하는 규칙 자체</b>였다.
 *
 * <p>그래서 위치 게이팅을 더 쌓는 대신 규칙을 바꿨다. 렌더러
 * ({@code ClaudePurchaseOrderBriefingGenerator.renderInput})는 일평균·소진 예상을 항상
 * 소수 1자리로 보여준다({@code String.format("%.1f", ...)}) — 즉 모델이 실제로 본 값은
 * "3.2"였지 "3"이 아니다. 프롬프트도 "숫자는 표에 있는 값만 쓴다. 더하거나 나누거나 평균
 * 내지 않는다"고 못박는다. 그러므로 인용된 숫자에 소수점이 있으면(자릿수 d≥1) 종전대로 그
 * 자릿수에 맞춰 반올림해 맞춰보고, 소수점 없는 정수 인용(d=0)은 실측치를 소수 1자리로
 * 반올림한 값이 이미 정수와 같을 때만(예: 8.0) 인정한다 — 그럴 때만 정수 인용이 표를 그대로
 * 옮긴 것이고, 아니면 모델이 스스로 반올림해서 지어낸 것이다.
 * <ul>
 *   <li>3.2333 → 소수 1자리 3.2 ≠ 정수 3 → "3" 불인정 (발주 세탁 차단)</li>
 *   <li>4.6875 → 소수 1자리 4.7 ≠ 정수 5 → "5" 불인정 ("가용은 5개" 차단)</li>
 *   <li>8.0 → 소수 1자리 8.0 = 정수 8 → "8" 인정 (진짜 정수 실측치는 정상 통과)</li>
 * </ul>
 *
 * <p>이 규칙 하나가 식별자·날짜 접두어를 잡던 DATE_PHRASE·ID_PHRASE를 대체한다. 정수로
 * 반올림해서 세탁하는 경로 자체가 막히므로 "이 숫자가 문장 어디에 있었는가"를 더 볼 필요가
 * 없다 — 두 패턴과 위치 게이팅 인프라(exactOnlyPositions)를 통째로 삭제했다. 화이트리스트
 * ({@link #WINDOW_PHRASE}, {@link #TOPN_PHRASE})는 남겨뒀다 — "상위 5개"의 5는 어떤 값의
 * 반올림도 아니라 애초에 근거 집합에 없는 숫자라서, 새 규칙으로도 걸러지지 않고 그대로
 * 오탐이 나기 때문이다.
 *
 * <p><b>남은 구멍:</b> 실측치가 우연히 정수로 딱 떨어지는 스냅샷(예: dailyAverage 8.0)에서는,
 * 그 정수와 같은 숫자를 엉뚱한 자리(잘못된 상품 번호·발주 번호·날짜)에 써도 이 채점기가
 * 걸러내지 못한다 — 위치 게이팅을 없앴으므로 값만 보고는 "표의 그 실측치를 인용한 것"과
 * "다른 필드를 가리키다 우연히 같은 정수가 된 것"을 구분할 수 없다. 정수 실측치가 있어야만
 * 성립하는 경우라 드물지만 이론적으로는 열려 있다. 같은 이유로 "최근 출고"(shippedQty)와
 * "표본"(sampleDays)도 접두어로 게이팅하지 않는다 — 둘 다 정확 집합에만 있어 반올림 경로를
 * 타지 않지만, 값이 겹치는 다른 필드의 정수 인용과는 구분되지 않는다.
 *
 * <p>NUMBER 패턴은 {@code \b}로 좌우 단어 경계를 요구한다 — 아니면 "A4용지"처럼 숫자가
 * 식별자에 박힌 상품명("A4", "3M" 등 영문+숫자 조합)에서 그 숫자가 인용으로 오독되어
 * 근거 없는 숫자로 잘못 잡힌다(분자·분모 둘 다 부풀어 환각률을 체계적으로 과대 측정한다).
 * Java의 {@code \b}는 {@code [a-zA-Z0-9_]}만 단어 문자로 보고 한글은 비단어로 취급하므로,
 * 영문자 바로 옆(A4)의 숫자만 경계가 없어 걸러지고 "#900"·"15개"·소수점 인용은 그대로
 * 잡힌다. 다만 이 경계도 한글 바로 뒤에 구분자 없이 붙은 숫자(가상의 예: "1호기")는 걸러내지
 * 못한다 — 한글이 비단어 문자라 그 자리엔 여전히 경계가 성립하기 때문이다. 현재 평가셋의
 * 상품명 중에는 그런 표기가 없어 실무상 열린 문제는 아니다.
 *
 * <p>반올림 규칙(d≥1): 인용된 숫자의 소수 자릿수에 맞춰 근거값을 반올림해 비교한다.
 * 3.2333은 "3.23"·"3.2" 모두 정상 인용이고 "3.4"는 아니다.
 *
 * <p>실제 평가 1회차에서 8건의 브리핑에 걸쳐 10개의 "근거 없는 숫자"가 잡혔는데, 대부분은
 * 환각이 아니라 앞자리 0이 붙은 날짜 구성요소였다. 렌더러가 {@code LocalDate}를 그대로
 * 이어붙여 ISO 형식("2026-08-01")을 보여주므로 모델은 "08"·"01"을 본 그대로 인용하는데,
 * 정확 집합은 {@code getMonthValue()}처럼 앞자리 0 없는 정수를 담아("8"·"1") 문자열이 달라
 * 오탐이 났다. 끝자리 0({@link #stripTrailingZeros})의 반대 문제라 같은 자리에
 * {@link #stripLeadingZeros}로 고쳤다 — 정수 인용만 앞자리 0을 지우고 다시 대조하며,
 * "0" 한 글자는 모든 값과 같아지지 않도록 그대로 둔다. 이 헤드라인 지표("환각 있는 브리핑
 * n/N")는 이 아티팩트들 때문에 실제보다 부풀려져 있었다.
 */
public final class GroundingScorer {

    // \b 경계가 없으면 "A4용지"의 4처럼 식별자 안에 박힌 숫자를 인용으로 오독한다.
    // Java 정규식의 \b는 [a-zA-Z0-9_]만 단어 문자로 보고 한글은 비단어로 취급하므로,
    // "A4"처럼 영문자 바로 뒤에 붙은 숫자만 걸러지고 "#900"·"15개"처럼 비단어 문자
    // 옆의 숫자는 그대로 걸린다.
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

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
        Set<Integer> whitelistPositions = whitelistPositions(body);

        List<String> ungrounded = new ArrayList<>();
        int total = 0;
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            String token = m.group();
            total++;
            boolean whitelisted = whitelistPositions.contains(m.start());
            if (!grounded(token, exact, measures, whitelisted)) ungrounded.add(token);
        }
        return new Result(total, ungrounded);
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
                                     boolean whitelisted) {
        if (exact.contains(token)) return true;
        if (exact.contains(stripTrailingZeros(token))) return true;
        if (exact.contains(stripLeadingZeros(token))) return true;
        if (whitelisted) return true;

        int decimals = token.contains(".") ? token.length() - token.indexOf('.') - 1 : 0;
        BigDecimal quoted = new BigDecimal(token);
        for (double v : measures) {
            BigDecimal value = BigDecimal.valueOf(v);
            if (decimals == 0) {
                // 정수 인용은 렌더가 보여준 소수 1자리 표현 자체가 정수일 때만 인정한다.
                // 그렇지 않으면(예: 3.2333 → 3.2) 모델이 스스로 반올림한 숫자를 표 값으로
                // 위장하는 것이다 — 이게 세 차례 재발한 세탁 버그의 근본 원인이었다.
                BigDecimal roundedTo1 = value.setScale(1, RoundingMode.HALF_UP);
                BigDecimal roundedTo0 = value.setScale(0, RoundingMode.HALF_UP);
                if (roundedTo1.compareTo(roundedTo0) != 0) continue;
                if (roundedTo0.compareTo(quoted) == 0) return true;
            } else if (value.setScale(decimals, RoundingMode.HALF_UP).compareTo(quoted) == 0) {
                return true;
            }
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

    /**
     * 렌더러({@code renderInput})는 날짜를 {@code LocalDate}를 그대로 이어붙여 보여주므로
     * ISO 형식("2026-08-01")이 되고, 월·일이 한 자리면 "08"·"01"처럼 0으로 채워진다.
     * 반면 정확 집합은 {@code getMonthValue()}·{@code getDayOfMonth()}를 {@code String.valueOf}로
     * 담아 "8"·"1"이다. 모델이 본 그대로("08")를 인용해도 문자열이 달라 오탐이 났다 —
     * 정수 토큰만(소수점 있는 토큰은 이미 measures 경로에서 BigDecimal로 정상 비교되므로
     * 건드리지 않는다) 앞자리 0을 지우고 다시 대조한다. "0" 한 글자는 그대로 둬 모든 값에
     * 걸리지 않게 한다.
     */
    private static String stripLeadingZeros(String token) {
        if (token.contains(".") || token.length() <= 1) return token;
        String stripped = token.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
