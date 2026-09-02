# 반품 상세 화면 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 반품 리포트의 표 한 칸을 눌러 그 칸에 든 반품들을 사유 원문·신뢰도까지 보는 화면을 만든다.

**Architecture:** 조회 축은 둘(상품·범주)이지만 화면은 하나다. 두 축이 같은 코호트에서 만든 같은 행 타입을 필터만 달리해 낸다. LLM 호출은 없다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Thymeleaf, JUnit 5, AssertJ, PostgreSQL 17

**설계 근거:** `docs/superpowers/specs/v5/2026-09-01-return-analytics-design.md` (V5.0 스펙)의 후속. 별도 스펙 없이 이 계획서가 설계를 겸한다 — 새 개념 없이 기존 코호트 조회에 축 하나를 더하는 작업이기 때문이다.

## 왜 이 화면이 필요한가

리포트는 "미분류 13건", "오배송 8건"까지 말하고 멈춘다. 그 앞에서 판단이 안 된다.

실제로 겪은 일이다. "미분류 13건을 소급 분류하자"고 시작했다가 DB를 직접 열어보고 나서야
**14건 중 13건이 `test`·`V2-3 폐기` 같은 개발 흔적**이라는 것을 알았다. 그 사실 하나가 계획
전체를 뒤집었다 — 안 열어봤으면 근거 없는 범주 13건을 넣을 뻔했다. 의사결정을 바꾸는
정보인데 지금은 DB에 직접 붙어야만 얻을 수 있다.

범주 쪽도 같다. "오배송 8건"으로는 조치가 안 된다. **그 8건이 한 상품에 몰렸는지 여덟 상품에
흩어졌는지**가 갈리는데 표가 말해주지 않는다.

신뢰도를 함께 내는 것에는 별도 이유가 있다. 1회차 분류 평가가 "confidence가 제 역할을 하는가"에
답하지 못했다(흔들린 케이스가 0건이라 표본이 없었다). 상세 화면에서 틀린 분류가 실제로 `LOW`를
받았는지 눈으로 보이면, 그 질문을 운영 데이터로 답하게 된다.

## Global Constraints

- **읽기 전용이다.** 재고·반품·분류를 만들거나 고치지 않는다. 서비스는 `@Transactional(readOnly = true)`.
- **LLM 호출을 넣지 않는다.** `ANTHROPIC_API_KEY`가 필요한 코드가 한 줄도 생기면 안 된다.
- **코호트 정의를 새로 만들지 않는다.** 기존 `cohort(from, to)` / `cohortReturns(...)`를 그대로 쓴다. 두 번째 정의가 생기면 리포트와 상세가 다른 숫자를 낸다.
- **필터·정렬·페이징을 넣지 않는다.** 한 범주의 반품이 몇백 건이 되면 그때 붙인다.
- 테스트 메서드 이름은 이 저장소 관례대로 한글이다. 주석도 한글이며 "왜"를 적는다.
- 커밋 메시지는 한글, `feat(wms):`/`test(wms):` 형식, 본문에 판단 근거, 트레일러 2줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
  ```
- 테스트 실행 전제: PostgreSQL 17 기동, DB `wms`/`wms_test`, 롤 `wms/wms`.
- 빌드 명령은 항상 `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home` 를 앞에 붙인다.
- **`README.md:16`의 테스트 수는 태스크마다 갱신한다.** 현재 389건이다. 389 → 392 → 395.

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java` | `ReturnDetailRow` + 축 둘 (`detailsByProduct`·`detailsByCategory`). 기존 `returnReasons`를 대체 |
| `src/main/java/com/jhg/wms/web/WmsAdminController.java` | `/admin/returns/report/detail` 매핑 추가 |
| `src/main/resources/templates/admin/return-detail-list.html` | 상세 목록 화면 |
| `src/main/resources/templates/admin/return-report.html` | 두 표의 칸에 링크 추가 |
| `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java` | 축 둘 검증 |
| `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` | 화면 검증 |

---

### Task 1: 조회 축 둘

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: 기존 private `cohort(from, to)`, `cohortReturns(cohort)`
- Produces:
  - `record ReturnAnalyticsService.ReturnDetailRow(Long rmaReturnId, Long orderId, Long productId, String productName, int requestedQuantity, String reason, ReturnCategory category, Confidence confidence)` — `category`·`confidence`가 `null`이면 미분류
  - `List<ReturnDetailRow> detailsByProduct(Long productId, LocalDate from, LocalDate to)`
  - `List<ReturnDetailRow> detailsByCategory(ReturnCategory category, LocalDate from, LocalDate to)` — `category`가 `null`이면 미분류만 낸다
- Removes: `ReturnReasonEntry`, `returnReasons(...)` — `detailsByProduct`가 같은 일을 더 많은 칸으로 한다. 호출자가 없으므로 지우는 것이 남기는 것보다 낫다(2단계 MCP 도구도 새 것을 쓴다).

- [ ] **Step 1: `categoriesOf`를 `classificationsOf`로 바꾼다**

상세 화면은 신뢰도까지 필요한데 지금은 범주만 꺼내고 있다. 같은 조회에서 엔티티를 그대로
들고 오면 쿼리가 늘지 않는다.

`ReturnAnalyticsService`의 `categoriesOf`를 통째로 아래로 바꾼다.

```java
    /** 반품 → 분류. 분류가 없는 반품은 키가 없다(미분류). */
    private Map<Long, ReturnClassification> classificationsOf(List<RmaReturn> returns) {
        if (returns.isEmpty()) return Map.of();
        List<Long> ids = returns.stream().map(RmaReturn::getId).toList();
        Map<Long, ReturnClassification> byReturn = new HashMap<>();
        for (ReturnClassification c : classificationRepository.findByRmaReturnIdIn(ids))
            byReturn.put(c.getRmaReturnId(), c);
        return byReturn;
    }
```

`categoryBreakdown` 안의 두 줄을 그에 맞춘다.

```java
        Map<Long, ReturnClassification> classificationByReturn = classificationsOf(returns);
```

```java
            ReturnCategory category = classificationByReturn.containsKey(r.getId())
                    ? classificationByReturn.get(r.getId()).getCategory() : null;
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`

먼저 `returnReasons`를 부르는 기존 테스트 **세 개**를 `detailsByProduct`로 바꾼다 — 인자
순서와 개수가 같아 호출 이름만 바뀐다.

| 테스트 | 바꿀 것 |
|---|---|
| `상품의_사유_원문을_코호트_안에서만_모은다` | `service.returnReasons(` → `service.detailsByProduct(` |
| `미분류_반품도_원문에_포함되고_범주는_비어_있다` | 같음 |
| `한_반품에_상품이_둘이면_수량이_상품별로_따로_붙는다` | 같음. 추가로 `ReturnAnalyticsService.ReturnReasonEntry::requestedQuantity` 를 `ReturnAnalyticsService.ReturnDetailRow::requestedQuantity` 로 |

`reason()`·`category()` 접근은 그대로 둔다 — 두 레코드의 필드 이름이 같다.

그 다음, 클래스 마지막 테스트 아래에 셋을 추가한다. 파일 상단 import에
`com.jhg.wms.domain.Confidence` 가 이미 있는지 확인하고 없으면 추가한다.

```java

    @Test
    void 범주_축은_그_범주의_반품만_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(101L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var rows = service.detailsByCategory(ReturnCategory.WRONG_ITEM,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement()
                .extracting(ReturnAnalyticsService.ReturnDetailRow::reason)
                .isEqualTo("다른 색이 왔어요");
    }

    // 미분류를 별도 메서드로 두지 않고 category=null로 받는다. 화면에서 미분류는
    // 범주 표의 다섯 번째 행이라, 같은 링크 구조로 열려야 한다.
    @Test
    void 범주_축에_null을_주면_미분류만_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var rows = service.detailsByCategory(null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo("그냥요");
            assertThat(row.category()).isNull();
            assertThat(row.confidence()).isNull();
        });
    }

    // 신뢰도를 내는 이유는 1회차 평가가 "confidence가 제 역할을 하는가"에 답하지 못했기
    // 때문이다. 틀린 분류가 실제로 LOW를 받았는지 화면에서 보여야 운영 데이터로 답한다.
    @Test
    void 분류된_반품은_신뢰도와_상품명을_함께_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 2, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);

        var rows = service.detailsByProduct(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.productName()).isEqualTo("상품 1");
            assertThat(row.category()).isEqualTo(ReturnCategory.WRONG_ITEM);
            assertThat(row.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(row.requestedQuantity()).isEqualTo(2);
        });
    }
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: 컴파일 실패 — `detailsByProduct`·`detailsByCategory`·`ReturnDetailRow` 심볼을 찾을 수 없다.

- [ ] **Step 4: 축 둘을 구현한다**

`ReturnAnalyticsService`에서 `ReturnReasonEntry` 레코드와 `returnReasons` 메서드를 **지우고**
그 자리에 아래를 넣는다. 파일 상단 import에 `com.jhg.wms.domain.Confidence` 를 추가한다.

```java
    /**
     * 상세 화면의 한 행. 반품 하나가 상품 둘을 담으면 행도 둘이다 — 수량이 품목마다 다르기 때문이다.
     *
     * category·confidence가 null이면 미분류다. 분류는 V4.0부터 붙어서 그 이전 반품에는 없다.
     */
    public record ReturnDetailRow(Long rmaReturnId, Long orderId, Long productId, String productName,
                                  int requestedQuantity, String reason,
                                  ReturnCategory category, Confidence confidence) {}

    public List<ReturnDetailRow> detailsByProduct(Long productId, LocalDate from, LocalDate to) {
        return allDetails(from, to).stream()
                .filter(row -> row.productId().equals(productId))
                .toList();
    }

    /** category가 null이면 미분류만 낸다 — 화면에서 미분류는 범주 표의 다섯 번째 행이다. */
    public List<ReturnDetailRow> detailsByCategory(ReturnCategory category, LocalDate from, LocalDate to) {
        return allDetails(from, to).stream()
                .filter(row -> row.category() == category)
                .toList();
    }

    /**
     * 코호트의 모든 품목을 행으로 편다. 두 축이 여기서 갈라지므로 정의가 하나로 유지된다.
     *
     * ponytail: 전부 만들고 메모리에서 거른다. 30일 창의 반품 품목 수가 이 규모에선 작다.
     * 한 축의 결과가 화면 한 장을 넘길 만큼 커지면 그때 저장소 쿼리로 내린다.
     */
    private List<ReturnDetailRow> allDetails(LocalDate from, LocalDate to) {
        List<RmaReturn> returns = cohortReturns(cohort(from, to));
        Map<Long, ReturnClassification> classificationByReturn = classificationsOf(returns);

        Map<Long, String> names = new HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(
                returns.stream().flatMap(r -> r.getItems().stream())
                        .map(RmaReturnItem::getProductId).collect(Collectors.toSet())))
            names.put(inv.getProductId(), inv.getProductName());

        List<ReturnDetailRow> rows = new ArrayList<>();
        for (RmaReturn r : returns) {
            ReturnClassification c = classificationByReturn.get(r.getId());
            for (RmaReturnItem i : r.getItems())
                rows.add(new ReturnDetailRow(r.getId(), r.getOrderId(), i.getProductId(),
                        Objects.requireNonNullElse(names.get(i.getProductId()), "(이름 없음)"),
                        i.getRequestedQuantity(), r.getReason(),
                        c == null ? null : c.getCategory(),
                        c == null ? null : c.getConfidence()));
        }
        return rows;
    }
```

파일 상단 import에 `java.util.stream.Collectors` 가 없으면 추가한다.

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: `BUILD SUCCESSFUL`, 15건 통과(기존 12 + 신규 3).

- [ ] **Step 6: 변이 검증**

`detailsByCategory`의 필터를

```java
                .filter(row -> row.category() == category)
```

아래로 바꾸고 테스트를 돌린다.

```java
                .filter(row -> category == null || row.category() == category)
```

Expected: `범주_축에_null을_주면_미분류만_낸다() FAILED` (미분류 1건이 아니라 전체 2건이 나온다).
확인 후 원복하고 다시 돌려 15건 통과를 확인한다.

- [ ] **Step 7: README 테스트 수를 갱신한다**

`README.md:16`의 `389개`를 `392개`로 바꾼다(389 + 신규 3).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java \
        src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 반품 상세 조회 — 상품 축과 범주 축

리포트는 "미분류 13건", "오배송 8건"까지 말하고 멈춘다. 그 앞에서 판단이 안 된다.
실제로 미분류 13건을 소급 분류하려다 DB를 직접 열어보고 나서야 그 대부분이
개발 흔적이라는 것을 알았다 — 안 열어봤으면 근거 없는 범주를 넣을 뻔했다.
의사결정을 바꾸는 정보인데 DB에 붙어야만 얻을 수 있었다.

축이 둘이지만 코호트 정의는 하나다. allDetails가 코호트의 품목을 전부 행으로
펴고 두 축이 거기서 갈라진다. 따로 만들면 리포트와 상세가 다른 숫자를 낸다.

미분류를 별도 메서드로 두지 않고 category=null로 받는다. 화면에서 미분류는
범주 표의 다섯 번째 행이라 같은 링크 구조로 열려야 한다.

신뢰도를 함께 낸다. 1회차 평가가 "confidence가 제 역할을 하는가"에 답하지
못했다(흔들린 케이스가 0건이라 표본이 없었다). 틀린 분류가 실제로 LOW를
받았는지 화면에서 보이면 그 질문을 운영 데이터로 답하게 된다.

returnReasons를 지웠다. detailsByProduct가 같은 일을 더 많은 칸으로 하고,
호출자가 없던 메서드라 남길 이유가 없다 — 2단계 MCP 도구도 새 것을 쓴다.

검증: 392건 그린(389 + 신규 3). 변이 검증 — 범주 축의 null 처리를 "전부"로
바꾸면 미분류 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

### Task 2: 상세 화면과 링크

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Create: `src/main/resources/templates/admin/return-detail-list.html`
- Modify: `src/main/resources/templates/admin/return-report.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`
- Modify: `README.md` (테스트 수 · 관리자 UI 표)

**Interfaces:**
- Consumes: `detailsByProduct`, `detailsByCategory`, `ReturnDetailRow` (Task 1)
- Produces: `GET /admin/returns/report/detail?from=&to=&productId=` 또는 `&category=` — 모델 속성 `rows`·`title`·`from`·`to`

**링크 규약:** 범주 표의 다섯 행이 전부 `category=` 하나로 열린다. 미분류는 `category=UNCLASSIFIED`다.
enum에 없는 값을 파라미터로 쓰는 셈이지만, 그래서 다섯 행의 링크 모양이 같아진다 — 미분류만
다른 파라미터를 쓰면 템플릿에 분기가 하나 더 생기고 그 분기가 링크를 조용히 어긋나게 만든다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`에 추가한다. 새 파일을
만들지 않는다 — 관리자 화면 테스트는 `@WebMvcTest(WmsAdminController.class)` 슬라이스
하나를 공유하고, 새 `@SpringBootTest`는 컨텍스트를 하나 더 살린다.

클래스 마지막 테스트 아래, 닫는 `}` 바로 위에 셋을 넣는다.

```java

    @Test
    void 반품상세_상품_축으로_열면_사유와_신뢰도가_렌더링된다() throws Exception {
        when(returnAnalyticsService.detailsByProduct(eq(11L), any(), any())).thenReturn(
                List.of(new ReturnAnalyticsService.ReturnDetailRow(
                        552L, 70000L, 11L, "상품 11", 2, "다른 색상이 왔어요",
                        ReturnCategory.WRONG_ITEM, Confidence.HIGH)));

        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("productId", "11")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/return-detail-list"))
                .andExpect(content().string(allOf(
                        containsString("다른 색상이 왔어요"),
                        containsString("오배송"),
                        containsString("높음"),
                        containsString("/admin/returns/552"))));   // 검수로 이어지는 링크
    }

    // 미분류는 범주 표의 다섯 번째 행이고 링크 모양이 나머지 넷과 같아야 한다.
    // 컨트롤러가 UNCLASSIFIED를 null로 바꿔 넘기는지가 이 테스트의 핵심이다.
    @Test
    void 반품상세_미분류로_열면_범주_없이_렌더링된다() throws Exception {
        when(returnAnalyticsService.detailsByCategory(isNull(), any(), any())).thenReturn(
                List.of(new ReturnAnalyticsService.ReturnDetailRow(
                        1L, 152L, 2L, "상품 2", 1, "test", null, null)));

        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("category", "UNCLASSIFIED")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("미분류"),
                        containsString("test"))));

        verify(returnAnalyticsService).detailsByCategory(isNull(),
                eq(LocalDate.of(2026, 3, 1)), eq(LocalDate.of(2026, 3, 31)));
    }

    // 축 없이 열리는 경우 — 사용자가 URL을 잘라 붙이면 생긴다. 500이 아니라 안내여야 한다.
    @Test
    void 반품상세_축을_안_주면_500이_아니라_안내를_낸다() throws Exception {
        mockMvc.perform(get("/admin/returns/report/detail")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상품이나 범주를 골라 주세요")));
    }
```

파일 상단 import에 `com.jhg.wms.domain.Confidence` 가 없으면 추가하고, 정적 import에
`org.mockito.ArgumentMatchers.isNull` 을 추가한다(`any`·`eq`는 이미 있다).

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'
```
Expected: 새 테스트 3건 실패 — `/admin/returns/report/detail`이 404다.

- [ ] **Step 3: 컨트롤러에 매핑을 추가한다**

`WmsAdminController`의 `returnReport` 메서드 아래에 넣는다. 파일 상단 import에
`com.jhg.wms.domain.ReturnCategory` 를 추가한다.

```java

    /**
     * 리포트 표의 한 칸에 든 반품들. 축은 상품 또는 범주 하나다.
     *
     * 범주 축의 UNCLASSIFIED는 enum에 없는 값이다. 그래도 이 파라미터로 받는 이유는
     * 범주 표의 다섯 행이 전부 같은 링크 모양으로 열리게 하기 위해서다 — 미분류만 다른
     * 파라미터를 쓰면 템플릿에 분기가 하나 더 생기고, 그 분기가 링크를 조용히 어긋나게 한다.
     */
    @GetMapping("/admin/returns/report/detail")
    public String returnReportDetail(@RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(required = false) Long productId,
                                     @RequestParam(required = false) String category,
                                     Model model) {
        if (to == null) to = LocalDate.now();
        if (from == null) from = to.minusDays(30);

        if (productId != null) {
            model.addAttribute("rows", returnAnalyticsService.detailsByProduct(productId, from, to));
            model.addAttribute("title", "상품 " + productId);
        } else if (category != null) {
            ReturnCategory parsed = "UNCLASSIFIED".equals(category) ? null : ReturnCategory.valueOf(category);
            model.addAttribute("rows", returnAnalyticsService.detailsByCategory(parsed, from, to));
            model.addAttribute("title", parsed == null ? "미분류" : parsed.label());
        } else {
            model.addAttribute("rows", null);
            model.addAttribute("errorMessage", "상품이나 범주를 골라 주세요.");
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/return-detail-list";
    }
```

- [ ] **Step 4: 템플릿을 만든다**

`src/main/resources/templates/admin/return-detail-list.html`

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 반품 상세')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('returnreport')}"></nav>
<main>
  <h2>반품 상세 <span th:if="${title != null}" th:text="'— ' + ${title}">— 미분류</span></h2>

  <div th:replace="~{fragments/layout :: flash}"></div>

  <p>
    <a th:href="@{/admin/returns/report(from=${from},to=${to})}">← 반품 리포트로</a>
    <span th:text="'(' + ${from} + ' ~ ' + ${to} + ')'">(기간)</span>
  </p>

  <p th:if="${errorMessage != null}" th:text="${errorMessage}">상품이나 범주를 골라 주세요.</p>

  <div class="table-wrap" th:if="${rows != null}">
  <table>
    <thead>
      <tr>
        <th>반품</th><th>주문</th><th>상품</th>
        <th style="text-align:right">수량</th>
        <th>사유</th><th>범주</th><th>신뢰도</th>
      </tr>
    </thead>
    <tbody>
      <tr th:each="row : ${rows}">
        <td><a th:href="@{'/admin/returns/' + ${row.rmaReturnId}}" th:text="${row.rmaReturnId}">1</a></td>
        <td th:text="${row.orderId}">100</td>
        <td th:text="${row.productName}">상품 1</td>
        <td style="text-align:right" th:text="${row.requestedQuantity}">1</td>
        <td th:text="${#strings.isEmpty(row.reason) ? '(사유 없음)' : row.reason}">사유</td>
        <td th:text="${row.category == null ? '미분류' : row.category.label()}">미분류</td>
        <td th:switch="${row.confidence == null ? 'NONE' : row.confidence.name()}">
          <span th:case="'HIGH'">높음</span>
          <span th:case="'MEDIUM'">보통</span>
          <span th:case="'LOW'">낮음</span>
          <span th:case="*">—</span>
        </td>
      </tr>
      <tr th:if="${#lists.isEmpty(rows)}">
        <td colspan="7">해당하는 반품이 없습니다.</td>
      </tr>
    </tbody>
  </table>
  </div>
</main>
</body>
</html>
```

- [ ] **Step 5: 리포트 표에 링크를 단다**

`src/main/resources/templates/admin/return-report.html`을 세 곳 고친다.

반품률 표의 상품명 칸:

```html
          <td th:text="${row.productName}">상품 1</td>
```

아래로 바꾼다.

```html
          <td><a th:href="@{/admin/returns/report/detail(from=${from},to=${to},productId=${row.productId})}"
                 th:text="${row.productName}">상품 1</a></td>
```

범주 표의 범주 칸:

```html
          <td th:text="${c.category.label()}">파손</td>
```

아래로 바꾼다.

```html
          <td><a th:href="@{/admin/returns/report/detail(from=${from},to=${to},category=${c.category.name()})}"
                 th:text="${c.category.label()}">파손</a></td>
```

미분류 행의 첫 칸:

```html
          <td>미분류</td>
          <td>미분류</td>
```

아래로 바꾼다.

```html
          <td><a th:href="@{/admin/returns/report/detail(from=${from},to=${to},category='UNCLASSIFIED')}">미분류</a></td>
          <td>미분류</td>
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'
```
Expected: `BUILD SUCCESSFUL`. 새 테스트 3건 포함 전부 그린.

- [ ] **Step 7: 변이 검증**

컨트롤러의

```java
            ReturnCategory parsed = "UNCLASSIFIED".equals(category) ? null : ReturnCategory.valueOf(category);
```

를 아래로 바꾸고 테스트를 돌린다.

```java
            ReturnCategory parsed = ReturnCategory.valueOf(category);
```

Expected: `반품상세_미분류로_열면_범주_없이_렌더링된다() FAILED` (`UNCLASSIFIED`는 enum에 없어
`IllegalArgumentException`이 난다).
확인 후 원복하고 다시 돌려 그린을 확인한다.

- [ ] **Step 8: 전체 스위트를 돌린다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew build --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`, 395건 그린. `ClassificationEvalTest`는 실행되지 않는다(태그 제외).

- [ ] **Step 9: README를 갱신한다**

두 곳을 고친다.

1. `README.md:16`의 `392개`를 `395개`로 바꾼다(392 + 신규 3).
2. 관리자 UI 표에서 `/admin/returns/report` 행 아래에 한 줄을 넣는다.

```markdown
| `/admin/returns/report/detail` | 반품 상세 — 리포트 표의 한 칸(상품 또는 범주·미분류)에 든 반품들, 사유 원문·신뢰도 포함 | 인증 |
```

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/resources/templates/admin/return-detail-list.html \
        src/main/resources/templates/admin/return-report.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 반품 상세 화면 — 리포트 표에서 한 칸을 열어본다

"오배송 8건"으로는 조치가 안 된다. 그 8건이 한 상품에 몰렸는지 여덟 상품에
흩어졌는지가 갈리는데 표가 말해주지 않았다. 미분류도 마찬가지다 — 건수만
보고는 분류를 못 한 이유를 알 수 없다.

범주 표의 다섯 행이 전부 category 파라미터 하나로 열린다. 미분류는
UNCLASSIFIED다. enum에 없는 값을 파라미터로 쓰는 셈이지만, 그래서 다섯 행의
링크 모양이 같아진다 — 미분류만 다른 파라미터를 쓰면 템플릿에 분기가 하나 더
생기고 그 분기가 링크를 조용히 어긋나게 만든다.

반품 ID를 기존 반품 상세로 링크한다. 화면이 관측에서 끝나지 않고 검수·처분으로
이어져야 한다.

축 없이 열리면 500이 아니라 안내를 낸다. URL을 잘라 붙이면 실제로 생긴다.

필터·정렬·페이징은 넣지 않았다. 한 범주의 반품이 화면 한 장을 넘기면 그때 붙인다.

검증: 395건 그린(392 + 신규 3). 변이 검증 — UNCLASSIFIED 분기를 빼면
미분류 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

## 완료 조건

- `./gradlew build` 395건 그린, `ClassificationEvalTest`는 실행되지 않는다
- 리포트의 상품명·범주·미분류를 눌러 상세로 갈 수 있고, 거기서 반품 상세로 이어진다
- 미분류 상세에 사유 원문이 그대로 보인다 — "왜 분류가 안 됐나"를 DB 없이 판단할 수 있다
- 코호트 정의가 여전히 하나다: `allDetails`가 `cohort`/`cohortReturns`를 그대로 쓴다
- `README.md:16` 테스트 수가 395, 관리자 UI 표에 상세 화면 행이 있다
- `.superpowers/sdd/progress.md`에 판단 근거 append
