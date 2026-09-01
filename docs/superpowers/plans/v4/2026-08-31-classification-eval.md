# 반품 사유 분류 품질 평가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 반품 사유 30건을 각 3회 분류해 정확도·흔들림·처분 매핑을 재고, 결과를 문서로 남긴다.

**Architecture:** 순수 집계(`EvalAggregator`)와 실제 API 호출(`ClassificationEvalTest`)을 분리한다. 집계는 오프라인 단위 테스트로 검증하고, 호출은 `@Tag("eval")`로 기본 `test`에서 제외해 CI가 과금하지 않게 한다. 전부 테스트 소스에만 산다 — 운영 코드는 한 줄도 바뀌지 않는다.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Jackson, Gradle, `com.anthropic:anthropic-java:2.34.0`

**설계 문서:** `docs/superpowers/specs/v4/2026-08-31-classification-eval-design.md`

## Global Constraints

- **운영 코드(`src/main`)를 수정하지 않는다.** 이 작업은 측정이지 변경이 아니다. 유일한 예외는 `build.gradle`의 태스크 설정이다.
- **CI가 과금하지 않아야 한다.** CI는 `./gradlew build`를 돌리고 그것이 `test`를 부른다. 실제 API를 부르는 테스트는 반드시 `@Tag("eval")`이고 기본 `test`에서 제외된다.
- **점수로 테스트를 실패시키지 않는다.** 임계값·단언 없음. 실패 조건은 오직 러너 자체의 예외다.
- 테스트 메서드 이름은 이 저장소 관례대로 한글이다. 주석도 한글이며 "왜"를 적는다.
- 커밋 메시지는 한글, `feat(wms):`/`test(wms):`/`docs(wms):` 형식, 본문에 판단 근거, 트레일러 2줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
  ```
- 테스트 실행 전제: PostgreSQL 17 기동(`brew services start postgresql@17`), DB `wms`/`wms_test`, 롤 `wms/wms`.
- 빌드 명령은 항상 `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home` 를 앞에 붙인다.
- 채점 대상은 `category` 하나다. `suggested_disposition`·`confidence`는 분포만 집계하고 정답과 대조하지 않는다. `evidence`는 다루지 않는다.
- **`README.md:16`의 테스트 수는 태스크마다 갱신한다.** 현재 360건이다. 기본 `test`에 추가되는 테스트만 세고, `@Tag("eval")` 테스트는 세지 않는다(기본 실행에 포함되지 않으므로).

## File Structure

| 파일 | 책임 |
|---|---|
| `src/test/java/com/jhg/wms/eval/EvalCase.java` | 평가셋 한 건의 레코드 (id·reason·expectedCategory·note) |
| `src/test/java/com/jhg/wms/eval/EvalObservation.java` | 한 번의 분류 관측 결과 레코드 |
| `src/test/java/com/jhg/wms/eval/EvalAggregator.java` | 순수 집계 — 다수결·흔들림·범주별 정확도·처분 매핑·토큰 합계 |
| `src/test/java/com/jhg/wms/eval/EvalReportWriter.java` | 집계 결과를 마크다운 문자열로 만든다 |
| `src/test/java/com/jhg/wms/eval/EvalAggregatorTest.java` | 집계 오프라인 검증 (기본 `test` 포함) |
| `src/test/java/com/jhg/wms/eval/EvalCaseLoadTest.java` | 평가셋 JSON 형식·개수 검증 (기본 `test` 포함) |
| `src/test/java/com/jhg/wms/eval/ClassificationEvalTest.java` | `@Tag("eval")` 실제 호출 러너 |
| `src/test/resources/eval/return-reasons.json` | 평가셋 30건 |
| `build.gradle:44` | `test`에서 `eval` 태그 제외, `evalTest` 태스크 등록 |
| `docs/wms-classification-eval.md` | 회차별 결과와 판단 |

`com.jhg.wms.eval` 패키지를 새로 만든다. 평가 도구는 운영 코드의 어느 계층에도 속하지 않으므로 기존 패키지에 섞지 않는다.

---

### Task 1: 평가셋과 로딩 검증

**Files:**
- Create: `src/test/resources/eval/return-reasons.json`
- Create: `src/test/java/com/jhg/wms/eval/EvalCase.java`
- Test: `src/test/java/com/jhg/wms/eval/EvalCaseLoadTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `record EvalCase(String id, String reason, ReturnCategory expectedCategory, String note)`
  - `static List<EvalCase> EvalCase.loadAll()` — 클래스패스의 `eval/return-reasons.json`을 읽어 반환. 읽기 실패 시 `UncheckedIOException`.

- [ ] **Step 1: 평가셋 JSON을 만든다**

`src/test/resources/eval/return-reasons.json` — 30건. `note`는 이 케이스가 왜 존재하는지 적는다.

```json
[
  { "id": "damaged-01", "reason": "택배 박스가 찌그러져서 안에 컵이 깨졌어요", "expectedCategory": "DAMAGED", "note": "파손이 명시적. 기본 케이스" },
  { "id": "damaged-02", "reason": "화면에 금이 가 있는 상태로 도착했습니다", "expectedCategory": "DAMAGED", "note": "파손이 명시적. 기본 케이스" },
  { "id": "damaged-03", "reason": "모서리가 다 까져서 왔네요", "expectedCategory": "DAMAGED", "note": "구어체 파손 표현" },
  { "id": "damaged-04", "reason": "뚜껑이 부러진 채로 배송됐어요", "expectedCategory": "DAMAGED", "note": "부품 파손" },
  { "id": "damaged-05", "reason": "긁힘이 있는데 쓰는 데는 문제없어요", "expectedCategory": "DAMAGED", "note": "매핑 탐침 — 파손이지만 재판매 가능. DISPOSED가 아닌 처분이 나오는지 본다" },
  { "id": "damaged-06", "reason": "포장은 멀쩡한데 안에 내용물이 새어 있었어요", "expectedCategory": "DAMAGED", "note": "매핑 탐침 — 파손 원인이 포장이 아님" },
  { "id": "damaged-07", "reason": "받자마자 열어보니 유리가 산산조각이던데요", "expectedCategory": "DAMAGED", "note": "감정 섞인 표현. 범주 판단이 흔들리는지" },

  { "id": "wrong-01", "reason": "주문한 색상이랑 다른 게 왔어요", "expectedCategory": "WRONG_ITEM", "note": "오배송이 명시적. 기본 케이스" },
  { "id": "wrong-02", "reason": "L 사이즈 시켰는데 S가 왔습니다", "expectedCategory": "WRONG_ITEM", "note": "옵션 불일치" },
  { "id": "wrong-03", "reason": "두 개 주문했는데 하나만 들어있어요", "expectedCategory": "WRONG_ITEM", "note": "수량 불일치 — 프롬프트가 수량도 WRONG_ITEM에 넣는다" },
  { "id": "wrong-04", "reason": "전혀 다른 상품이 배송됐네요", "expectedCategory": "WRONG_ITEM", "note": "상품 자체가 다름" },
  { "id": "wrong-05", "reason": "구성품 중에 케이블이 빠져 있어요", "expectedCategory": "WRONG_ITEM", "note": "매핑 탐침 — 누락. 재입고가 자연스러운지" },
  { "id": "wrong-06", "reason": "제품은 맞는데 사은품이 안 왔어요", "expectedCategory": "WRONG_ITEM", "note": "부분 불일치. OTHER로 새는지 본다" },
  { "id": "wrong-07", "reason": "송장은 제 이름인데 물건이 제가 시킨 게 아니에요", "expectedCategory": "WRONG_ITEM", "note": "서술이 긴 오배송" },

  { "id": "mind-01", "reason": "생각보다 커서 안 맞네요", "expectedCategory": "CHANGED_MIND", "note": "사이즈 불만족. 실제 관측된 케이스(rmaId=402)" },
  { "id": "mind-02", "reason": "사이즈 교환하려구요", "expectedCategory": "CHANGED_MIND", "note": "교환 의사. 하자 언급 없음" },
  { "id": "mind-03", "reason": "필요 없어져서 반품합니다", "expectedCategory": "CHANGED_MIND", "note": "프롬프트에 명시된 전형" },
  { "id": "mind-04", "reason": "포장만 뜯었어요", "expectedCategory": "CHANGED_MIND", "note": "매핑 탐침 — 미개봉에 가까움. RESTOCKED가 나오는지" },
  { "id": "mind-05", "reason": "몇 번 써봤는데 안 맞아서요", "expectedCategory": "CHANGED_MIND", "note": "매핑 탐침 — 사용 흔적. REJECTED가 나오는지가 핵심" },
  { "id": "mind-06", "reason": "색이 화면이랑 달라서 마음에 안 들어요", "expectedCategory": "CHANGED_MIND", "note": "경계 — 오배송(WRONG_ITEM)으로 새기 쉽다. 하자가 아니라 취향이다" },
  { "id": "mind-07", "reason": "한 달 정도 쓰다가 안 쓰게 됐어요", "expectedCategory": "CHANGED_MIND", "note": "매핑 탐침 — 기간 경과. REJECTED가 나오는지" },
  { "id": "mind-08", "reason": "선물했는데 상대방이 이미 갖고 있대요", "expectedCategory": "CHANGED_MIND", "note": "제3자 사유. 범주가 흔들리는지" },

  { "id": "other-01", "reason": "배송기사님이 너무 불친절했어요", "expectedCategory": "OTHER", "note": "명백한 범주 밖. OTHER가 안 나오면 프롬프트 문제다" },
  { "id": "other-02", "reason": "그냥요", "expectedCategory": "OTHER", "note": "정보 없음. 프롬프트대로면 OTHER + LOW" },
  { "id": "other-03", "reason": "음... 그냥 별로네요", "expectedCategory": "OTHER", "note": "실제 관측(rmaId=452)에서 CHANGED_MIND+LOW가 나왔다. 재현되는지" },
  { "id": "other-04", "reason": "배송이 너무 늦게 와서요", "expectedCategory": "OTHER", "note": "물건이 아니라 배송에 대한 불만" },
  { "id": "other-05", "reason": "결제가 중복으로 됐어요", "expectedCategory": "OTHER", "note": "결제 이슈 — 창고 범주 밖" },
  { "id": "other-06", "reason": "ㅁㄴㅇㄹ", "expectedCategory": "OTHER", "note": "의미 없는 입력. 억지로 범주를 맞추지 않는지" },
  { "id": "other-07", "reason": "반품이요", "expectedCategory": "OTHER", "note": "사유가 아닌 동작만 적음" },
  { "id": "other-08", "reason": "환불 부탁드립니다", "expectedCategory": "OTHER", "note": "요청만 있고 사유 없음. CHANGED_MIND로 새기 쉽다" }
]
```

- [ ] **Step 2: `EvalCase`를 만든다**

`src/test/java/com/jhg/wms/eval/EvalCase.java`

```java
package com.jhg.wms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.ReturnCategory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 평가셋 한 건.
 *
 * note는 장식이 아니다 — 나중에 점수가 흔들렸을 때 이 케이스가 왜 여기 있는지를
 * 알아야 판단할 수 있다. 라벨만 남으면 반년 뒤 해석이 불가능하다.
 */
public record EvalCase(String id, String reason, ReturnCategory expectedCategory, String note) {

    private static final String PATH = "eval/return-reasons.json";

    public static List<EvalCase> loadAll() {
        try (var in = new ClassPathResource(PATH).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("평가셋을 읽지 못했습니다: " + PATH, e);
        }
    }
}
```

- [ ] **Step 3: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/EvalCaseLoadTest.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.ReturnCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가셋 자체를 검증한다. 유료 실행을 돌린 뒤에야 "id가 겹쳤다"를 아는 일이 없도록,
 * 형식 문제는 공짜로 먼저 잡는다.
 */
class EvalCaseLoadTest {

    private final List<EvalCase> cases = EvalCase.loadAll();

    @Test
    void 서른_건이_설계대로_배분돼_있다() {
        Map<ReturnCategory, Long> 배분 = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::expectedCategory, Collectors.counting()));

        assertThat(cases).hasSize(30);
        assertThat(배분).containsExactlyInAnyOrderEntriesOf(Map.of(
                ReturnCategory.DAMAGED, 7L,
                ReturnCategory.WRONG_ITEM, 7L,
                ReturnCategory.CHANGED_MIND, 8L,
                ReturnCategory.OTHER, 8L));
    }

    @Test
    void id가_겹치지_않는다() {
        assertThat(cases.stream().map(EvalCase::id).distinct()).hasSize(cases.size());
    }

    // note 없는 케이스는 나중에 해석이 불가능해진다. 라벨만 남는 것을 막는다.
    @Test
    void 모든_케이스에_사유와_근거가_있다() {
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.reason()).as("reason: %s", c.id()).isNotBlank();
            assertThat(c.note()).as("note: %s", c.id()).isNotBlank();
        });
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*EvalCaseLoadTest*'
```
Expected: `BUILD SUCCESSFUL`, 3건 통과.

- [ ] **Step 5: 변이 검증 — 평가셋을 망가뜨려 테스트가 잡는지 본다**

`return-reasons.json`에서 `other-08` 항목 하나를 삭제하고 위 명령을 다시 돌린다.
Expected: `서른_건이_설계대로_배분돼_있다() FAILED` (29건, OTHER 7건).
확인 후 삭제한 항목을 원복하고 다시 돌려 통과를 확인한다.

- [ ] **Step 6: README 테스트 수를 갱신한다**

`README.md:16`의 `360개`를 `363개`로 바꾼다(360 + 신규 3).

- [ ] **Step 7: 커밋**

```bash
git add src/test/resources/eval/return-reasons.json \
        src/test/java/com/jhg/wms/eval/EvalCase.java \
        src/test/java/com/jhg/wms/eval/EvalCaseLoadTest.java \
        README.md
git commit -F - <<'EOF'
test(wms): 분류 품질 평가셋 30건과 형식 검증

정확도 측정용과 매핑 탐침용을 한 셋에 섞었다. 후자는 category가 정해지면
suggested_disposition이 따라오는지 보려는 케이스로, 사용 흔적·기간 경과·
재판매 가능한 파손처럼 매핑을 깨뜨릴 만한 사유를 일부러 심었다. 운영
데이터로는 이런 케이스가 우연히 들어오길 기다려야 해서 몇 달이 걸린다.

OTHER에 8건으로 가장 많이 배정했다. 실제 관측 5건에서 한 번도 나오지 않은
범주이고, 프롬프트는 "애매하면 OTHER"라고 지시하는데 모델이 따르지 않는
정황이 있다(rmaId=452). 중립적 배분이 아니라 가설을 겨냥한 배분이므로,
전체 정확도만 보지 말고 범주별로 읽어야 한다.

note를 필수로 검증한다. 라벨만 남으면 반년 뒤 이 케이스가 왜 여기 있는지
해석할 수 없다.

검증: 363건 그린(360 + 신규 3). 변이 검증 — 케이스를 하나 지우면
배분 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

### Task 2: 집계 — 다수결·흔들림·범주별 정확도

**Files:**
- Create: `src/test/java/com/jhg/wms/eval/EvalObservation.java`
- Create: `src/test/java/com/jhg/wms/eval/EvalAggregator.java`
- Test: `src/test/java/com/jhg/wms/eval/EvalAggregatorTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: `EvalCase` (Task 1)
- Produces:
  - `record EvalObservation(String caseId, ReturnCategory category, Confidence confidence, RmaDisposition disposition, int inputTokens, int outputTokens, String model)` — `category`가 `null`이면 분류 실패(모델이 empty를 반환). `model`은 응답이 돌려준 확정 스냅샷이다.
  - `EvalAggregator.CaseResult` (중첩 레코드) — `(EvalCase source, ReturnCategory majority, boolean unstable, List<EvalObservation> observations)`
  - `EvalAggregator.Summary` (중첩 레코드) — `(int total, int correct, Map<ReturnCategory, int[]> perCategory, int unstableCount, Map<ReturnCategory, Map<RmaDisposition, Integer>> dispositionByCategory, Map<Confidence, Integer> confidenceDistribution, Map<Confidence, Integer> confidenceOfUnstable, int failedObservations, long inputTokens, long outputTokens)`
    - `perCategory`의 `int[]`는 `{맞은 수, 전체 수}` 두 칸이다.
  - `static Summary EvalAggregator.summarize(List<CaseResult> results)`
  - `static CaseResult EvalAggregator.toCaseResult(EvalCase source, List<EvalObservation> observations)`

- [ ] **Step 1: `EvalObservation`을 만든다**

`src/test/java/com/jhg/wms/eval/EvalObservation.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

/**
 * 한 번의 분류 관측.
 *
 * category가 null이면 분류 실패다(classify()가 empty를 반환). 실패를 별도 타입으로
 * 나누지 않는 이유는, 집계가 "몇 번 실패했나"만 알면 되고 원인은 로그의 몫이기 때문이다.
 *
 * model은 응답이 돌려준 확정 스냅샷이다(claude-haiku-4-5-20251001). 요청에 쓴 별칭이
 * 아니라 이 값을 리포트에 적어야 나중에 어느 버전에서 잰 점수인지 알 수 있다.
 */
public record EvalObservation(String caseId,
                              ReturnCategory category,
                              Confidence confidence,
                              RmaDisposition disposition,
                              int inputTokens,
                              int outputTokens,
                              String model) {

    public static EvalObservation failed(String caseId) {
        return new EvalObservation(caseId, null, null, null, 0, 0, null);
    }

    public boolean succeeded() {
        return category != null;
    }
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/EvalAggregatorTest.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 집계를 API 없이 검증한다. 집계가 틀리면 유료 실행 결과를 통째로 잘못 읽게 되므로,
 * 이 부분은 공짜로 확인해야 한다.
 */
class EvalAggregatorTest {

    private EvalCase 케이스(String id, ReturnCategory expected) {
        return new EvalCase(id, "사유 " + id, expected, "테스트용");
    }

    private EvalObservation 관측(String id, ReturnCategory c, Confidence conf, RmaDisposition d) {
        return new EvalObservation(id, c, conf, d, 1000, 40, "claude-haiku-4-5-20251001");
    }

    @Test
    void 세_번_같은_답이면_안정이고_그_답이_다수결이다() {
        var result = EvalAggregator.toCaseResult(
                케이스("a", ReturnCategory.DAMAGED),
                List.of(관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("a", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        assertThat(result.majority()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(result.unstable()).isFalse();
    }

    @Test
    void 답이_갈리면_흔들림으로_표시하고_최빈값을_다수결로_삼는다() {
        var result = EvalAggregator.toCaseResult(
                케이스("b", ReturnCategory.OTHER),
                List.of(관측("b", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("b", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("b", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED)));

        assertThat(result.majority()).isEqualTo(ReturnCategory.OTHER);
        assertThat(result.unstable()).isTrue();
    }

    // 세 답이 모두 다르면 최빈값이 없다. 이때는 다수결을 null로 두어
    // "판단 불가"를 오답과 구분한다 — 오답으로 세면 정확도가 실제보다 나빠 보인다.
    @Test
    void 세_답이_모두_다르면_다수결이_없다() {
        var result = EvalAggregator.toCaseResult(
                케이스("c", ReturnCategory.OTHER),
                List.of(관측("c", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("c", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("c", ReturnCategory.DAMAGED, Confidence.LOW, RmaDisposition.DISPOSED)));

        assertThat(result.majority()).isNull();
        assertThat(result.unstable()).isTrue();
    }

    @Test
    void 실패한_관측은_다수결에서_빠지고_따로_센다() {
        var result = EvalAggregator.toCaseResult(
                케이스("d", ReturnCategory.DAMAGED),
                List.of(관측("d", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        EvalObservation.failed("d"),
                        관측("d", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(result));

        assertThat(result.majority()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(result.unstable()).isFalse();
        assertThat(summary.failedObservations()).isEqualTo(1);
    }

    @Test
    void 요약이_정확도와_범주별_성적을_낸다() {
        var 맞음 = EvalAggregator.toCaseResult(
                케이스("e", ReturnCategory.DAMAGED),
                List.of(관측("e", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));
        var 틀림 = EvalAggregator.toCaseResult(
                케이스("f", ReturnCategory.OTHER),
                List.of(관측("f", ReturnCategory.CHANGED_MIND, Confidence.MEDIUM, RmaDisposition.RESTOCKED)));

        var summary = EvalAggregator.summarize(List.of(맞음, 틀림));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.correct()).isEqualTo(1);
        assertThat(summary.perCategory().get(ReturnCategory.DAMAGED)).containsExactly(1, 1);
        assertThat(summary.perCategory().get(ReturnCategory.OTHER)).containsExactly(0, 1);
    }

    @Test
    void 처분_매핑을_범주별로_모은다() {
        var a = EvalAggregator.toCaseResult(
                케이스("g", ReturnCategory.CHANGED_MIND),
                List.of(관측("g", ReturnCategory.CHANGED_MIND, Confidence.HIGH, RmaDisposition.RESTOCKED),
                        관측("g", ReturnCategory.CHANGED_MIND, Confidence.HIGH, RmaDisposition.REJECTED)));

        var summary = EvalAggregator.summarize(List.of(a));

        assertThat(summary.dispositionByCategory().get(ReturnCategory.CHANGED_MIND))
                .containsEntry(RmaDisposition.RESTOCKED, 1)
                .containsEntry(RmaDisposition.REJECTED, 1);
    }

    // confidence가 제 역할을 하는지 보려면 "흔들린 케이스에서 낮게 주는가"를 봐야 한다.
    @Test
    void 흔들린_케이스의_신뢰도를_따로_모은다() {
        var 흔들림 = EvalAggregator.toCaseResult(
                케이스("h", ReturnCategory.OTHER),
                List.of(관측("h", ReturnCategory.OTHER, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("h", ReturnCategory.CHANGED_MIND, Confidence.LOW, RmaDisposition.RESTOCKED),
                        관측("h", ReturnCategory.OTHER, Confidence.MEDIUM, RmaDisposition.RESTOCKED)));
        var 안정 = EvalAggregator.toCaseResult(
                케이스("i", ReturnCategory.DAMAGED),
                List.of(관측("i", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(흔들림, 안정));

        assertThat(summary.confidenceOfUnstable())
                .containsEntry(Confidence.LOW, 2)
                .containsEntry(Confidence.MEDIUM, 1)
                .doesNotContainKey(Confidence.HIGH);
        assertThat(summary.confidenceDistribution()).containsEntry(Confidence.HIGH, 1);
    }

    @Test
    void 토큰을_합산한다() {
        var a = EvalAggregator.toCaseResult(
                케이스("j", ReturnCategory.DAMAGED),
                List.of(관측("j", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED),
                        관측("j", ReturnCategory.DAMAGED, Confidence.HIGH, RmaDisposition.DISPOSED)));

        var summary = EvalAggregator.summarize(List.of(a));

        assertThat(summary.inputTokens()).isEqualTo(2000);
        assertThat(summary.outputTokens()).isEqualTo(80);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*EvalAggregatorTest*'
```
Expected: 컴파일 실패 — `EvalAggregator` 심볼을 찾을 수 없다.

- [ ] **Step 4: `EvalAggregator`를 구현한다**

`src/test/java/com/jhg/wms/eval/EvalAggregator.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 순수 집계. API도 파일도 모른다 — 그래서 공짜로 검증된다. */
public final class EvalAggregator {

    private EvalAggregator() {}

    public record CaseResult(EvalCase source,
                             ReturnCategory majority,
                             boolean unstable,
                             List<EvalObservation> observations) {}

    /** perCategory의 int[]는 {맞은 수, 전체 수}다. */
    public record Summary(int total,
                          int correct,
                          Map<ReturnCategory, int[]> perCategory,
                          int unstableCount,
                          Map<ReturnCategory, Map<RmaDisposition, Integer>> dispositionByCategory,
                          Map<Confidence, Integer> confidenceDistribution,
                          Map<Confidence, Integer> confidenceOfUnstable,
                          int failedObservations,
                          long inputTokens,
                          long outputTokens) {}

    public static CaseResult toCaseResult(EvalCase source, List<EvalObservation> observations) {
        Map<ReturnCategory, Integer> 표 = new EnumMap<>(ReturnCategory.class);
        for (EvalObservation o : observations)
            if (o.succeeded()) 표.merge(o.category(), 1, Integer::sum);

        int 최다 = 표.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // 최빈값이 여럿이면 판단 불가다. 임의로 하나를 고르면 정확도가 우연에 좌우된다.
        List<ReturnCategory> 최빈 = 표.entrySet().stream()
                .filter(e -> e.getValue() == 최다).map(Map.Entry::getKey).toList();
        ReturnCategory majority = 최빈.size() == 1 ? 최빈.get(0) : null;

        boolean unstable = 표.size() > 1;
        return new CaseResult(source, majority, unstable, observations);
    }

    public static Summary summarize(List<CaseResult> results) {
        int correct = 0, unstableCount = 0, failed = 0;
        long in = 0, out = 0;
        Map<ReturnCategory, int[]> perCategory = new EnumMap<>(ReturnCategory.class);
        Map<ReturnCategory, Map<RmaDisposition, Integer>> byCategory = new EnumMap<>(ReturnCategory.class);
        Map<Confidence, Integer> confAll = new EnumMap<>(Confidence.class);
        Map<Confidence, Integer> confUnstable = new EnumMap<>(Confidence.class);

        for (CaseResult r : results) {
            ReturnCategory expected = r.source().expectedCategory();
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
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*EvalAggregatorTest*'
```
Expected: `BUILD SUCCESSFUL`, 8건 통과.

- [ ] **Step 6: 변이 검증**

`EvalAggregator.toCaseResult`의 다음 줄을

```java
        ReturnCategory majority = 최빈.size() == 1 ? 최빈.get(0) : null;
```

아래로 바꾸고 테스트를 돌린다.

```java
        ReturnCategory majority = 최빈.isEmpty() ? null : 최빈.get(0);
```

Expected: `세_답이_모두_다르면_다수결이_없다() FAILED`.
확인 후 원복하고 다시 돌려 8건 통과를 확인한다.

- [ ] **Step 7: README 테스트 수를 갱신한다**

`README.md:16`의 `363개`를 `371개`로 바꾼다(363 + 신규 8).

- [ ] **Step 8: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/EvalObservation.java \
        src/test/java/com/jhg/wms/eval/EvalAggregator.java \
        src/test/java/com/jhg/wms/eval/EvalAggregatorTest.java \
        README.md
git commit -F - <<'EOF'
test(wms): 평가 집계를 API 없이 검증한다

집계가 틀리면 유료 실행 결과를 통째로 잘못 읽게 된다. 그래서 다수결·흔들림·
범주별 성적·처분 매핑·토큰 합계를 API를 모르는 순수 함수로 떼어내고, 가짜
관측으로 검증한다. 이 테스트는 돈이 들지 않으므로 기본 test에 넣는다.

판단 하나를 명시한다. 세 답이 모두 다르면 다수결을 null로 두어 "판단 불가"를
오답과 구분한다. 임의로 하나를 고르면 정확도가 우연에 좌우되고, 오답으로
세면 정확도가 실제보다 나빠 보인다.

흔들린 케이스의 신뢰도를 따로 모은다. confidence가 제 역할을 하는지는
"모델이 흔들리는 곳에서 실제로 낮게 주는가"로만 확인할 수 있다.

검증: 371건 그린(363 + 신규 8). 변이 검증 — 최빈값이 여럿일 때 첫 값을
고르도록 바꾸면 판단 불가 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

### Task 3: 마크다운 리포트 생성

**Files:**
- Create: `src/test/java/com/jhg/wms/eval/EvalReportWriter.java`
- Test: `src/test/java/com/jhg/wms/eval/EvalReportWriterTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: `EvalAggregator.Summary`, `EvalAggregator.CaseResult` (Task 2)
- Produces:
  - `static String EvalReportWriter.render(String model, int repeats, List<CaseResult> results, EvalAggregator.Summary summary)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/EvalReportWriterTest.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportWriterTest {

    private EvalAggregator.CaseResult 결과(String id, ReturnCategory expected, ReturnCategory got) {
        var c = new EvalCase(id, "사유 " + id, expected, "테스트용");
        return EvalAggregator.toCaseResult(c, List.of(
                new EvalObservation(id, got, Confidence.HIGH, RmaDisposition.DISPOSED, 1000, 40,
                        "claude-haiku-4-5-20251001")));
    }

    // 모델 스냅샷이 없는 점수는 나중에 해석되지 않는다. 리포트에 반드시 있어야 한다.
    @Test
    void 리포트에_모델_스냅샷과_정확도가_들어간다() {
        var results = List.of(결과("a", ReturnCategory.DAMAGED, ReturnCategory.DAMAGED),
                              결과("b", ReturnCategory.OTHER, ReturnCategory.CHANGED_MIND));
        String report = EvalReportWriter.render(
                "claude-haiku-4-5-20251001", 3, results, EvalAggregator.summarize(results));

        assertThat(report)
                .contains("claude-haiku-4-5-20251001")
                .contains("1/2")
                .contains("처분 매핑")
                .contains("누적 토큰");
    }

    // 틀린 케이스는 id와 note까지 나와야 한다 — 왜 틀렸는지 보려면 그 케이스를 찾아가야 한다.
    @Test
    void 틀린_케이스를_id와_함께_나열한다() {
        var results = List.of(결과("b", ReturnCategory.OTHER, ReturnCategory.CHANGED_MIND));
        String report = EvalReportWriter.render(
                "claude-haiku-4-5-20251001", 3, results, EvalAggregator.summarize(results));

        assertThat(report).contains("b").contains("OTHER").contains("CHANGED_MIND");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*EvalReportWriterTest*'
```
Expected: 컴파일 실패 — `EvalReportWriter` 심볼을 찾을 수 없다.

- [ ] **Step 3: `EvalReportWriter`를 구현한다**

`src/test/java/com/jhg/wms/eval/EvalReportWriter.java`

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*EvalReportWriterTest*'
```
Expected: `BUILD SUCCESSFUL`, 2건 통과.

- [ ] **Step 5: 변이 검증**

`EvalReportWriter.render`에서 모델을 적는 줄을

```java
        sb.append("- 모델: `").append(model).append("`\n");
```

아래로 바꾸고 테스트를 돌린다.

```java
        sb.append("- 모델: (생략)\n");
```

Expected: `리포트에_모델_스냅샷과_정확도가_들어간다() FAILED`.
확인 후 원복하고 다시 돌려 2건 통과를 확인한다.

- [ ] **Step 6: README 테스트 수를 갱신한다**

`README.md:16`의 `371개`를 `373개`로 바꾼다(371 + 신규 2).

- [ ] **Step 7: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/EvalReportWriter.java \
        src/test/java/com/jhg/wms/eval/EvalReportWriterTest.java \
        README.md
git commit -F - <<'EOF'
test(wms): 평가 결과를 마크다운 리포트로 만든다

모델 스냅샷을 반드시 적는다. claude-haiku-4-5-20251001에서 잰 점수는 그
버전의 점수지 영원한 점수가 아니라, 스냅샷 없는 숫자는 나중에 해석되지 않는다.
테스트가 그 줄의 존재를 고정한다.

틀리거나 흔들린 케이스는 id와 note까지 함께 낸다. 왜 틀렸는지 보려면 그
케이스가 왜 평가셋에 있었는지를 알아야 하기 때문이다.

처분 매핑 표에 "같은 범주에 항상 같은 처분이 붙으면 매핑 테이블로 대체할 수
있다"는 한 줄을 넣는다. 표만 있으면 무엇을 보라는 것인지 알기 어렵다.

검증: 373건 그린(371 + 신규 2). 변이 검증 — 모델 줄을 지우면 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

### Task 4: 실행 분리 — `evalTest` 태스크

**Files:**
- Modify: `build.gradle:44-46`

**Interfaces:**
- Consumes: 없음
- Produces: `./gradlew evalTest` 명령. `eval` 태그가 붙은 테스트만 돈다.

- [ ] **Step 1: `build.gradle`을 고친다**

현재 내용(`build.gradle:44-46`):

```groovy
tasks.named('test') {
	useJUnitPlatform()
}
```

아래로 바꾼다.

```groovy
// 실제 Claude API를 부르는 평가는 기본 test에서 뺀다.
// CI가 ./gradlew build를 돌리고 그것이 test를 부르므로, 여기서 빼지 않으면
// 매 push마다 과금된다. 이 저장소의 첫 @Tag 사용이다.
tasks.named('test') {
	useJUnitPlatform {
		excludeTags 'eval'
	}
}

// 분류 품질 평가 전용. 실행: ./gradlew evalTest (ANTHROPIC_API_KEY 필요)
tasks.register('evalTest', Test) {
	group = 'verification'
	description = '반품 사유 분류 품질 평가 — 실제 API를 호출하며 과금된다'
	useJUnitPlatform {
		includeTags 'eval'
	}
	testClassesDirs = sourceSets.test.output.classesDirs
	classpath = sourceSets.test.runtimeClasspath
	// 매번 실제로 돌아야 의미가 있다. Gradle이 "변경 없음"으로 건너뛰지 않게 한다.
	outputs.upToDateWhen { false }
	testLogging {
		showStandardStreams = true
	}
}
```

- [ ] **Step 2: 태스크가 등록됐는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew tasks --group verification
```
Expected: 목록에 `evalTest - 반품 사유 분류 품질 평가 — 실제 API를 호출하며 과금된다` 가 보인다.

- [ ] **Step 3: 기본 test가 여전히 그린인지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. 아직 `eval` 태그를 붙인 테스트가 없으므로 373건 그대로다.

- [ ] **Step 4: 커밋**

```bash
git add build.gradle
git commit -F - <<'EOF'
build(wms): 평가 실행을 기본 test에서 분리한다

CI는 ./gradlew build를 돌리고 그것이 test를 부른다. 실제 API를 부르는
평가를 기본 test에 두면 매 push마다 과금된다. excludeTags로 빼는 것이
이 작업 전체에서 가장 중요한 안전장치라, 러너를 쓰기 전에 먼저 넣는다.

outputs.upToDateWhen { false }를 둔다. 입력이 안 바뀌었다고 Gradle이
건너뛰면 "평가를 돌렸다"고 착각하게 된다 — 이 태스크는 매번 실제로 돌아야
의미가 있다.

이 저장소의 첫 @Tag 사용이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

### Task 5: 실제 호출 러너

**Files:**
- Create: `src/test/java/com/jhg/wms/eval/ClassificationEvalTest.java`

**Interfaces:**
- Consumes: `EvalCase.loadAll()` (Task 1), `EvalAggregator` (Task 2), `EvalReportWriter.render(...)` (Task 3), `evalTest` 태스크 (Task 4)
- Produces: `build/reports/classification-eval.md`

- [ ] **Step 1: 러너를 쓴다**

`src/test/java/com/jhg/wms/eval/ClassificationEvalTest.java`

```java
package com.jhg.wms.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudeReturnReasonClassifier;
import com.jhg.wms.service.ReturnReasonClassifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Claude API를 호출하는 품질 평가. 기본 test에서 제외돼 있다 — 실행은 ./gradlew evalTest.
 *
 * 점수로 실패하지 않는다. 임계값을 두면 비결정적 출력 때문에 언젠가 반드시 헛경보가 나고,
 * 지금 목적은 회귀 게이트가 아니라 측정이다. 실패 조건은 오직 "러너가 못 돌았다"이다.
 */
@Tag("eval")
class ClassificationEvalTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final int REPEATS = 3;
    private static final int PARALLELISM = 5;
    private static final Path REPORT = Path.of("build/reports/classification-eval.md");

    @Test
    void 분류_품질을_재고_리포트를_남긴다() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        // 키가 없으면 실패가 아니라 스킵이다. 이 테스트를 못 돌리는 것은 사고가 아니다.
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY 미설정 — 평가를 건너뜁니다.");

        ReturnReasonClassifier classifier = new ClaudeReturnReasonClassifier(
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .timeout(Duration.ofSeconds(20))
                        .maxRetries(1)
                        .build(),
                new ObjectMapper(), MODEL, 1024L);

        List<EvalCase> cases = EvalCase.loadAll();
        List<EvalAggregator.CaseResult> results = new ArrayList<>();
        String 실제모델 = MODEL;

        // 순차로 돌리면 90회 × 약 6초 = 9분이다. 동시 5면 2분 안쪽이고 rate limit에도 여유가 있다.
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            for (EvalCase c : cases) {
                List<Future<EvalObservation>> futures = new ArrayList<>();
                for (int i = 0; i < REPEATS; i++)
                    futures.add(pool.submit(observe(classifier, c)));

                List<EvalObservation> observations = new ArrayList<>();
                for (Future<EvalObservation> f : futures) observations.add(f.get());
                results.add(EvalAggregator.toCaseResult(c, observations));
            }
        } finally {
            pool.shutdown();
        }

        // 모델이 돌려준 확정 스냅샷을 쓴다. 별칭(claude-haiku-4-5)으로 적으면
        // 나중에 어느 버전에서 잰 점수인지 알 수 없다.
        for (EvalAggregator.CaseResult r : results)
            for (EvalObservation o : r.observations())
                if (o.succeeded() && o.model() != null) { 실제모델 = o.model(); break; }

        var summary = EvalAggregator.summarize(results);
        String report = EvalReportWriter.render(실제모델, REPEATS, results, summary);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        // 유일한 단언: 러너가 실제로 돌았는가. 점수는 판단하지 않는다.
        assertThat(results).hasSize(cases.size());
    }

    private Callable<EvalObservation> observe(ReturnReasonClassifier classifier, EvalCase c) {
        return () -> classifier.classify(c.reason())
                .map(r -> new EvalObservation(c.id(), r.category(), r.confidence(),
                        r.suggestedDisposition(), r.inputTokens(), r.outputTokens(), r.model()))
                .orElseGet(() -> EvalObservation.failed(c.id()));
    }
}
```

- [ ] **Step 2: 기본 test가 그린이고 평가가 제외됐는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, 373건. `ClassificationEvalTest`는 **실행되지 않는다**(태그 제외).

확인:
```bash
ls build/test-results/test/ | grep -c ClassificationEvalTest
```
Expected: `0`

- [ ] **Step 3: 키 없이 evalTest가 스킵되는지 확인한다**

Run (키를 일부러 비운다):
```bash
env -u ANTHROPIC_API_KEY JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew evalTest
```
Expected: `BUILD SUCCESSFUL`. 실패가 아니라 스킵이다.

- [ ] **Step 4: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/ClassificationEvalTest.java
git commit -F - <<'EOF'
test(wms): 실제 API로 분류 품질을 재는 러너 (eval 태그)

각 사유를 3회 분류한다. 1회로는 틀린 케이스가 실력인지 흔들림인지 구분할 수
없고, confidence가 제 역할을 하는지도 볼 수 없다. 90회를 순차로 돌리면
9분이라 동시 5로 돌린다.

점수로 실패하지 않는다. 유일한 단언은 "케이스 수만큼 결과가 모였는가"다.
임계값을 두면 비결정적 출력 때문에 언젠가 반드시 헛경보가 나고, 지금 목적은
회귀 게이트가 아니라 측정이다.

키가 없으면 실패가 아니라 스킵이다. 이 테스트를 못 돌리는 것은 사고가 아니다.

리포트는 build/reports에 쓴다. 커밋 대상 문서를 직접 덮어쓰면 수동으로 적어둔
해석이 재실행에 날아간다. 문서로 옮기는 것은 사람이 한다.

모델은 응답이 돌려준 확정 스냅샷을 적는다. 요청에 쓴 별칭으로 적으면 나중에
어느 버전에서 잰 점수인지 알 수 없다.

검증: 기본 test 373건 그린이고 이 테스트는 실행되지 않는다(태그 제외).
키를 비우고 evalTest를 돌리면 스킵된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

### Task 6: 실제 실행과 결과 문서

**Files:**
- Create: `docs/wms-classification-eval.md`
- Modify: `README.md` (V4.0 절에 평가 문서 링크 한 줄)

**Interfaces:**
- Consumes: `./gradlew evalTest` (Task 4, 5)
- Produces: 결과 문서

- [ ] **Step 1: 평가를 실행한다**

`ANTHROPIC_API_KEY`가 셸에 있어야 한다. 없으면 사용자에게 요청한다 — 이 단계는 실제로 과금된다(약 150원).

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew evalTest
```
Expected: `BUILD SUCCESSFUL`, 콘솔에 리포트 출력, `build/reports/classification-eval.md` 생성.

- [ ] **Step 2: 리포트를 읽는다**

```bash
cat build/reports/classification-eval.md
```

세 가지를 확인한다. 이것이 설계 문서의 성공 기준이다.

1. 전체·범주별 정확도가 나왔는가
2. `처분 매핑` 표에서 같은 범주에 여러 처분이 붙었는가 (붙었으면 모델에게 물을 값이고, 하나뿐이면 매핑으로 충분하다)
3. `신뢰도` 표에서 흔들린 케이스가 실제로 낮은 신뢰도를 받았는가

- [ ] **Step 3: 결과 문서를 쓴다**

`docs/wms-classification-eval.md`를 만들고, `build/reports/classification-eval.md`의 내용을 옮긴 뒤 **판단을 덧붙인다.**

문서 머리말(고정):

```markdown
# 반품 사유 분류 품질 평가

측정 방법과 설계 근거는 [설계 문서](superpowers/specs/v4/2026-08-31-classification-eval-design.md)에 있다.

실행: `./gradlew evalTest` (`ANTHROPIC_API_KEY` 필요, 1회 약 150원)

**라벨은 Claude가 초안을 쓰고 사람이 검수했다.** 최종 권위는 사람에게 있다 —
모델이 만든 라벨로 모델의 프롬프트를 채점하면 순환이기 때문이다.

**점수는 모델 스냅샷에 매인다.** 아래 각 회차에 적힌 버전에서 잰 값이고,
모델이 바뀌면 다시 재야 한다.

---
```

그 아래에 회차를 붙인다. 리포트 내용에 이어 다음 절을 **직접 작성**한다(수치를 보고 판단해야 하므로 미리 쓸 수 없다):

```markdown
## 이 회차에서 판단한 것

### 1. 정확도
(전체·범주별 수치를 인용하고, 어느 범주가 약한지와 그 이유 추정을 적는다)

### 2. suggested_disposition은 모델에게 물을 값인가
(처분 매핑 표를 근거로 답한다. 같은 범주에 처분이 하나뿐이면 "매핑으로 충분하다",
갈렸으면 "모델에게 물을 값이다"로 결론짓고 갈린 케이스의 id를 적는다)

### 3. confidence는 제 역할을 하는가
(흔들린 케이스의 신뢰도 분포를 근거로 답한다)

### 다음에 할 일
(위 판단에서 따라 나오는 것만 적는다. 없으면 "없음"이라고 적는다)
```

숫자만 쌓이고 판단이 없으면 문서가 아니라 로그다.

- [ ] **Step 4: README에 링크를 건다**

`README.md`의 `### 반품 사유 자동 분류 (V4.0)` 절 안, "비용 관측" 문단 뒤에 한 줄을 넣는다.

```markdown
품질은 30건 평가셋으로 측정했습니다 — [측정 결과](docs/wms-classification-eval.md).
```

- [ ] **Step 5: 커밋**

```bash
git add docs/wms-classification-eval.md README.md
git commit -F - <<'EOF'
docs(wms): 분류 품질 1회차 측정 결과와 판단

<모델 스냅샷>에서 30건 × 3회를 돌려 다수결 정확도 <N/30>을 얻었다.
범주별로는 <가장 약한 범주>가 <n/m>으로 가장 낮았다.

suggested_disposition은 <매핑으로 충분하다 | 모델에게 물을 값이다>.
근거: <처분이 갈린 범주와 케이스 id, 또는 모든 범주에서 처분이 하나뿐이었다는 사실>.

confidence는 <제 역할을 한다 | 하지 않는다>. 흔들린 <k>건 중 <j>건이
LOW 또는 MEDIUM을 받았다.

점수가 좋든 나쁘든 이 작업의 목적은 재는 것이었고, 이제 프롬프트를 고칠 때
비교할 기준이 생겼다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01G6kbQ7Cz5k2MPWcZxkSpyg
EOF
```

---

## 완료 조건

- `./gradlew test` 373건 그린, `ClassificationEvalTest`는 실행되지 않는다
- `./gradlew evalTest`가 키 없이는 스킵, 키가 있으면 리포트를 남긴다
- `docs/wms-classification-eval.md`에 1회차 결과와 **세 질문에 대한 판단**이 적혀 있다
- `README.md:16` 테스트 수가 373, V4.0 절에 평가 문서 링크가 있다
- `.superpowers/sdd/progress.md`에 판단 근거 append
