# 수불대장 드릴다운 구현 계획 (WMS V7.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수불대장의 상품 행을 클릭하면 그 상품·그 기간의 개별 이동이 열리고, 화면에서 `기초 + 이동 = 기말`이 확인된다.

**Architecture:** 새 화면을 만들지 않는다. 이미 있는 재고 트랜잭션 이력(`/admin/inventory/transactions`)에 상품·기간 필터를 더해 드릴다운 대상으로 쓰고, 수불대장 행에서 그리로 링크한다. 대조 줄의 숫자는 수불대장을 만드는 `buildLedger`에서 그대로 꺼내 두 화면이 구조적으로 어긋날 수 없게 한다.

**Tech Stack:** Java 21 · Spring Boot 3.5.5 · Spring Data JPA · PostgreSQL 17 · Thymeleaf

**근거 스펙:** `docs/superpowers/specs/v7/2026-09-01-ledger-drilldown-design.md` (커밋 `707975f`)

---

## Global Constraints

- 브랜치: `feat/wms-ledger-drilldown` (`master`에서 분기, 이미 체크아웃됨)
- 테스트: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
  - 전제: 로컬 PostgreSQL 17 기동, `wms_test` DB 준비됨
- 테스트 baseline: **389건 그린**. 매 태스크 끝에 전체 스위트가 그린이어야 하고 건수는 증가만 한다.
  (각 태스크의 예상 건수는 상한이 아니다 — 실제 빈틈을 닫는 테스트 추가는 환영이다.)
- Java 4스페이스. 주석은 한국어로, **왜**만. 코드가 이미 말하는 "무엇"은 쓰지 않는다.
- 커밋 메시지: 한국어, 무엇이 아니라 **왜**. 마지막 줄 트레일러:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **기간은 반개구간 `[from 00:00, to+1일 00:00)`.** `buildLedger`가 쓰는 경계와 같아야 한다 — 다르면 같은 기간을 두 방식으로 세게 되어 수불대장과 상세가 어긋난다(이 기능이 막으려는 바로 그 일이다).
- 행 클릭은 기존 방식을 따른다: `tr`에 `th:data-href`, 템플릿 안에 `main` 리스너. CSS `tbody tr[data-href]{cursor:pointer}`는 이미 전역에 있다. 선례는 `admin/returns.html`·`admin/cycle-counts.html`.
- 비범위(스펙 그대로): 불변식 위반 표의 행 클릭, 상품 검색·자동완성, CSV 내보내기, 기초재고 열 드릴다운, 셀 단위 클릭, 키보드 접근 경로.

---

## ⚠️ 착수 전 필독 — PostgreSQL 파라미터 타입 추론

선택적 필터 네 개를 `(:param IS NULL OR ...)` 로 쓰는 흔한 패턴이 **이 프로젝트에서는 깨진다.** 계획 단계에서 실제 `wms_test`에 돌려 확인했다.

```
JDBC exception executing SQL [... where (? is null or it1_0.type=?)
  and (? is null or it1_0.product_id=?) and (? is null or it1_0.created_at >= ?) ...]
org.postgresql.util.PSQLException: ERROR: could not determine data type of parameter $5
```

**깨지는 것은 enum이 아니라 `LocalDateTime` null이다** (`$5` = 첫 번째 날짜 파라미터).

같은 조건에서 **날짜만 항상 실값으로 넘기면 통과한다** — `type`·`productId`의 `IS NULL OR`는 문제없다. 네 가지 필터 조합(전체 / 상품만 / 유형만 / 유형+상품)을 모두 실측했다.

그래서 이 계획은 **날짜를 서비스에서 항상 실값으로 채워 넘긴다.** 범위가 없으면 데이터가 있을 수 없는 넓은 경계로 대체한다. 이건 취향이 아니라 위 실측에 대한 대응이므로, 임의로 `(:from IS NULL OR ...)` 형태로 되돌리지 말 것.

---

## File Structure

**수정 (production)**

| 파일 | 책임 |
|------|------|
| `src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java` | 유형·상품·기간 조합 페이지 조회 1개 추가 |
| `src/main/java/com/jhg/wms/service/InventoryService.java` | 범위 인자를 받는 `findTransactions`, 한 상품의 `LedgerRow` 조회 |
| `src/main/java/com/jhg/wms/web/WmsAdminController.java` | `inventoryTransactions`가 세 파라미터 수신 + 대조용 모델 |
| `src/main/resources/templates/admin/inventory-ledger.html` | 상품 행에 `data-href`, 클릭 리스너 |
| `src/main/resources/templates/admin/inventory-transactions.html` | 범위 배지·범위 해제·대조 줄, 탭·페이징이 범위 유지 |

**수정 (test)**

| 파일 | 무엇 |
|------|------|
| `src/test/java/com/jhg/wms/service/InventoryServiceTest.java` | 필터·경계·등식 |
| `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` | 링크 렌더·범위 UI·대조 줄 |

**분해 근거:** T1이 데이터 층을 닫고, T2가 도착 화면을 단독으로 쓸 수 있게 만들고, T3이 두 화면을 잇고, T4가 이은 결과가 맞는지를 화면에서 증명한다. T2가 T3보다 먼저인 이유는 순서를 뒤집으면 링크가 범위를 못 받는 화면으로 떨어지기 때문이다.

---

### Task 1: 유형·상품·기간 조합 조회

**Files:**
- Modify: `src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java`
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java`
- Test: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java`

**Interfaces:**
- Consumes: 기존 `InventoryTransaction`, `InventoryTransactionType`, `LedgerRow`
- Produces:
  - `InventoryTransactionRepository.search(InventoryTransactionType type, Long productId, LocalDateTime from, LocalDateTime to, Pageable pageable)` → `Page<InventoryTransaction>`
  - `InventoryService.findTransactions(InventoryTransactionType type, Long productId, LocalDate from, LocalDate to, Pageable pageable)` → `Page<InventoryTransaction>` (기존 2인자 페이징 메서드를 **대체**)
  - `InventoryService.ledgerRowOf(Long productId, LocalDate from, LocalDate to)` → `Optional<LedgerRow>`

- [ ] **Step 1: baseline 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL. `build/reports/tests/test/index.html`의 건수를 기록한다(예상 389).

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/service/InventoryServiceTest.java` 끝(클래스 닫는 중괄호 앞)에 추가.

먼저 파일 상단 import 블록에 없으면 추가:
```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
```

```java
    // ── 드릴다운 조회 ────────────────────────────────────────────

    /** createdAt은 of()가 now()로 박으므로, 기간 경계를 시험하려면 심어서 넣어야 한다. */
    private void seedTxnAt(Long productId, InventoryTransactionType type, int delta, LocalDateTime at) {
        var txn = InventoryTransaction.of(productId, type, delta, 0, delta, null, null, "test");
        ReflectionTestUtils.setField(txn, "createdAt", at);
        txnRepo.save(txn);
    }

    @Test
    void 상품으로_좁히면_다른_상품은_안_나온다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));

        var page = inventoryService.findTransactions(null, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getProductId)
                .containsOnly(1L);
    }

    // 반개구간 [from 00:00, to+1일 00:00). buildLedger와 같은 경계여야 수불대장과 상세가 맞는다.
    @Test
    void 종료일_당일은_포함하고_다음날은_제외한다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 1, LocalDateTime.of(2026, 9, 30, 23, 59));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 2, LocalDateTime.of(2026, 10, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 3, LocalDateTime.of(2026, 8, 31, 23, 59));

        var page = inventoryService.findTransactions(null, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getDelta)
                .containsExactlyInAnyOrder(1);
    }

    @Test
    void 유형과_상품과_기간을_함께_건다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -4, LocalDateTime.of(2026, 9, 5, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.SHIP, -7, LocalDateTime.of(2026, 9, 5, 10, 0));

        var page = inventoryService.findTransactions(InventoryTransactionType.SHIP, 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InventoryTransaction::getDelta)
                .containsExactly(-4);
    }

    // 범위를 안 걸면 전건이 나와야 한다 — 날짜를 넓은 경계로 대체하는 처리가 조용히 걸러내면 안 된다.
    @Test
    void 범위를_안_걸면_전건이_나온다() {
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 10, LocalDateTime.of(2020, 1, 1, 0, 0));
        seedTxnAt(2L, InventoryTransactionType.SHIP, -4, LocalDateTime.of(2030, 12, 31, 0, 0));

        var page = inventoryService.findTransactions(null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void 한_상품의_수불행을_꺼낸다() {
        seedTxnAt(1L, InventoryTransactionType.OPENING, 100, LocalDateTime.of(2026, 8, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -15, LocalDateTime.of(2026, 9, 11, 10, 0));

        var row = inventoryService.ledgerRowOf(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                .orElseThrow();

        assertThat(row.opening()).isEqualTo(100);
        assertThat(row.closing()).isEqualTo(105);
    }

    @Test
    void 트랜잭션이_전혀_없는_상품은_수불행이_없다() {
        assertThat(inventoryService.ledgerRowOf(999L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
    }
```

`txnRepo`·`inventoryService` 필드명이 이 파일에서 다르면 파일에 이미 있는 이름을 쓴다.

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest'
```
Expected: 컴파일 실패 — `method findTransactions ... cannot be applied to given types` / `cannot find symbol: method ledgerRowOf`

- [ ] **Step 4: 리포지토리 조회 추가**

`InventoryTransactionRepository`에 추가(기존 `findByTypeOrderByIdDesc(type, pageable)` 아래):

```java
    /**
     * 관리자 화면 드릴다운용 — 유형·상품·기간 조합.
     *
     * 날짜에 (:from IS NULL OR ...) 를 쓰지 않는다. PostgreSQL이 null 파라미터의 타입을
     * 추론하지 못해 "could not determine data type of parameter" 로 깨진다(실측).
     * 호출부가 범위 없음을 넓은 경계 값으로 바꿔 넘긴다.
     */
    @Query("SELECT t FROM InventoryTransaction t " +
           "WHERE (:type IS NULL OR t.type = :type) " +
           "AND (:productId IS NULL OR t.productId = :productId) " +
           "AND t.createdAt >= :from AND t.createdAt < :to " +
           "ORDER BY t.id DESC")
    Page<InventoryTransaction> search(@Param("type") InventoryTransactionType type,
                                      @Param("productId") Long productId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      Pageable pageable);
```

- [ ] **Step 5: 서비스 메서드 교체·추가**

`InventoryService`의 기존 페이징 메서드를 아래로 **교체**한다(2인자 버전은 남기지 않는다 — 호출부가 컨트롤러 하나뿐이라 경로를 둘로 둘 이유가 없다):

```java
    // 범위가 없을 때 날짜에 넣는 경계. PostgreSQL이 null 날짜 파라미터의 타입을 추론하지 못해
    // (:from IS NULL OR ...) 형태가 깨지므로, 조회는 항상 실값 두 개를 받는다.
    // 이 시스템에 있을 수 없는 시각이라 결과를 거르지 않는다.
    private static final LocalDateTime NO_LOWER_BOUND = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime NO_UPPER_BOUND = LocalDateTime.of(9999, 12, 31, 0, 0);

    /** 페이징 이력 조회. 상품·기간은 선택이고, 기간은 buildLedger와 같은 반개구간이다. */
    public org.springframework.data.domain.Page<InventoryTransaction> findTransactions(
            InventoryTransactionType type, Long productId, LocalDate from, LocalDate to,
            org.springframework.data.domain.Pageable pageable) {
        return transactionRepository.search(type, productId,
                from == null ? NO_LOWER_BOUND : from.atStartOfDay(),
                to == null ? NO_UPPER_BOUND : to.plusDays(1).atStartOfDay(),
                pageable);
    }

    /**
     * 한 상품의 수불 행. buildLedger를 그대로 불러 골라낸다 —
     * 계산식을 새로 짜면 수불대장과 대조 줄이 서로 다른 코드가 되어 언젠가 어긋난다.
     */
    public Optional<LedgerRow> ledgerRowOf(Long productId, LocalDate from, LocalDate to) {
        return buildLedger(from, to).stream()
                .filter(row -> row.productId().equals(productId))
                .findFirst();
    }
```

파일 상단 import에 `java.time.LocalDateTime`·`java.util.Optional`이 없으면 추가한다.

- [ ] **Step 6: 컨트롤러 호출부 맞추기(컴파일 통과용 최소 변경)**

`WmsAdminController.inventoryTransactions`의 호출 한 줄을 바꾼다. 파라미터 추가는 Task 2에서 한다.

```java
        var txnPage = inventoryService.findTransactions(type, null, null, null, pageable);
```

- [ ] **Step 7: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest'
```
Expected: PASS (기존 + 6개)

- [ ] **Step 8: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 395건

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java \
        src/main/java/com/jhg/wms/service/InventoryService.java \
        src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/test/java/com/jhg/wms/service/InventoryServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 이동내역을 유형·상품·기간으로 조회한다

수불대장에서 행을 눌러도 도착 화면이 유형으로만 걸리면 거기 나온 숫자가 방금 누른
행과 맞지 않는다. 요약과 상세가 어긋나면 어느 쪽이 틀렸는지 알 수 없어 둘 다
의심하게 되므로, 상세를 같은 상품·같은 기간으로 좁힐 수 있어야 한다.

기간은 buildLedger와 같은 반개구간을 쓴다. 경계가 다르면 같은 기간을 두 방식으로
세게 되어 이 기능이 막으려는 어긋남을 이 기능이 만든다.

날짜에는 (:from IS NULL OR ...) 를 쓰지 않았다. PostgreSQL이 null 파라미터의 타입을
추론하지 못해 could not determine data type of parameter 로 깨진다 — 호출부가
범위 없음을 넓은 경계 값으로 바꿔 넘긴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 이동내역 화면에 범위를 붙인다

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Modify: `src/main/resources/templates/admin/inventory-transactions.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `findTransactions(type, productId, from, to, pageable)`
- Produces: `/admin/inventory/transactions`가 `productId`·`from`·`to` 쿼리 파라미터를 받고, 모델에 같은 이름으로 되싣는다

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`에 추가.

```java
    @Test
    void 이력화면이_상품과_기간을_받아_서비스에_넘긴다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("productId", 1L))
                .andExpect(model().attribute("from", java.time.LocalDate.of(2026, 9, 1)))
                .andExpect(model().attribute("to", java.time.LocalDate.of(2026, 9, 30)));

        verify(inventoryService).findTransactions(null, 1L,
                java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30), any());
    }

    // 탭이 범위를 떨어뜨리면 유형을 누를 때마다 전역 저널로 튕겨 드릴다운이 한 번 쓰고 끝난다.
    @Test
    void 유형탭과_범위해제_링크가_범위를_유지하거나_턴다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 유형 탭은 범위를 실어 나른다
        assertThat(html).contains("type=RECEIVE").contains("productId=1")
                .contains("from=2026-09-01").contains("to=2026-09-30");
        // 범위 해제는 상품·기간을 떼고 유형만 남긴다
        assertThat(html).containsPattern("href=\"[^\"]*/admin/inventory/transactions\"[^>]*>\\s*범위 해제");
    }

    @Test
    void 범위가_없으면_범위배지를_렌더하지_않는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("범위 해제");
    }
```

`assertThat` (AssertJ) import가 없으면 `import static org.assertj.core.api.Assertions.assertThat;`를 추가한다. 기존 테스트가 `findTransactions`를 2인자로 스텁하고 있으면 5인자로 고친다.

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest'
```
Expected: 컴파일 실패 또는 `model attribute 'productId' does not exist`

- [ ] **Step 3: 컨트롤러 수정**

`WmsAdminController.inventoryTransactions`를 아래로 교체:

```java
    @GetMapping("/admin/inventory/transactions")
    public String inventoryTransactions(@RequestParam(required = false) InventoryTransactionType type,
                                        @RequestParam(required = false) Long productId,
                                        @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to,
                                        @RequestParam(defaultValue = "0") int page,
                                        Model model) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, 20);
        var txnPage = inventoryService.findTransactions(type, productId, from, to, pageable);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        model.addAttribute("transactions", txnPage.getContent());
        model.addAttribute("currentPage", txnPage.getNumber());
        model.addAttribute("totalPages", txnPage.getTotalPages());
        model.addAttribute("filterType", type);
        // 템플릿이 탭·페이징 링크에 그대로 실어 나른다 — 없으면 유형을 누를 때마다 범위가 풀린다.
        model.addAttribute("productId", productId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/inventory-transactions";
    }
```

- [ ] **Step 4: 템플릿 수정 — 범위 배지와 탭·페이징 링크**

`admin/inventory-transactions.html`에서 `<h2>` 다음, 유형 탭 `<p>` 앞에 배지를 넣는다:

```html
  <!-- 범위가 걸렸을 때만. 빈 껍데기를 두면 전역 저널에도 의미 없는 줄이 남는다. -->
  <p th:if="${productId != null or from != null}" class="hint">
    <span th:if="${productId != null}"
          th:text="${productNames.getOrDefault(productId, '상품#' + productId)}">상품 1</span>
    <span th:if="${from != null}"
          th:text="' · ' + ${from} + ' ~ ' + ${to}"> · 2026-09-01 ~ 2026-09-30</span>
    <a th:href="@{/admin/inventory/transactions(type=${filterType})}">범위 해제</a>
  </p>
```

유형 탭 `<p>` 전체를 아래로 교체(각 링크가 범위 세 개를 싣는다):

```html
  <p>
    <a th:href="@{/admin/inventory/transactions(productId=${productId},from=${from},to=${to})}">전체</a>
    | <a th:href="@{/admin/inventory/transactions(type='OPENING',productId=${productId},from=${from},to=${to})}">기초</a>
    | <a th:href="@{/admin/inventory/transactions(type='RECEIVE',productId=${productId},from=${from},to=${to})}">입고</a>
    | <a th:href="@{/admin/inventory/transactions(type='SHIP',productId=${productId},from=${from},to=${to})}">출고</a>
    | <a th:href="@{/admin/inventory/transactions(type='ADJUST',productId=${productId},from=${from},to=${to})}">조정</a>
    | <a th:href="@{/admin/inventory/transactions(type='RETURN',productId=${productId},from=${from},to=${to})}">반품</a>
    | <a th:href="@{/admin/inventory/transactions(type='COUNT',productId=${productId},from=${from},to=${to})}">실사</a>
  </p>
```

페이징 블록의 링크 세 곳(`이전`, 숫자, `다음`)에도 같은 세 파라미터를 더한다. 예:

```html
    <a th:if="${currentPage > 0}"
       th:href="@{/admin/inventory/transactions(type=${filterType},productId=${productId},from=${from},to=${to},page=${currentPage - 1})}">← 이전</a>
```
```html
        <a th:unless="${i == currentPage}"
           th:href="@{/admin/inventory/transactions(type=${filterType},productId=${productId},from=${from},to=${to},page=${i})}"
           th:text="${i + 1}">1</a>
```
```html
    <a th:if="${currentPage + 1 < totalPages}"
       th:href="@{/admin/inventory/transactions(type=${filterType},productId=${productId},from=${from},to=${to},page=${currentPage + 1})}">다음 →</a>
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest'
```
Expected: PASS

- [ ] **Step 6: 단언이 무는지 확인**

유형 탭의 `RECEIVE` 링크에서 `productId=${productId}`를 잠시 지우고 실행:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest'
```
Expected: `유형탭과_범위해제_링크가_범위를_유지하거나_턴다` FAIL. 확인 후 **되돌린다**.

- [ ] **Step 7: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 398건

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/resources/templates/admin/inventory-transactions.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 이동내역에 상품·기간 범위를 걸 수 있게 한다

유형 탭과 페이징 링크가 범위를 실어 나르게 했다. 안 그러면 유형을 한 번 누르는
순간 전역 저널로 튕겨서, 드릴다운으로 들어와도 한 번 보고 끝난다.

범위 배지는 범위가 걸렸을 때만 낸다 — 전역 저널에 빈 껍데기가 남으면 "지금 뭘로
걸러져 있나"를 화면에서 읽을 수 없게 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 수불대장 행에서 잇는다

**Files:**
- Modify: `src/main/resources/templates/admin/inventory-ledger.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 2가 받는 `productId`·`from`·`to` 쿼리 파라미터
- Produces: 없음(화면)

- [ ] **Step 1: 실패하는 테스트 작성**

`WmsAdminControllerTest`에 추가. 기존 수불대장 테스트가 쓰는 스텁 방식을 따른다(`inventoryService.buildLedger(...)`를 스텁).

```java
    @Test
    void 수불대장_상품행은_상품과_기간을_실은_링크를_들고_있다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));
        when(inventoryService.findInvariantViolations(any())).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/inventory/ledger")
                        .with(user("op").roles("OPERATOR"))
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
                "data-href=\"/admin/inventory/transactions?productId=1&amp;from=2026-09-01&amp;to=2026-09-30\"");
    }

    // 합계 행은 상품이 아니다 — 눌리면 productId 없는 링크로 떨어진다.
    @Test
    void 수불대장_합계행에는_링크가_붙지_않는다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));
        when(inventoryService.findInvariantViolations(any())).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/inventory/ledger")
                        .with(user("op").roles("OPERATOR"))
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher tfoot = java.util.regex.Pattern
                .compile("<tfoot.*?</tfoot>", java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(tfoot.find()).as("tfoot 합계 블록이 렌더돼야 한다").isTrue();
        assertThat(tfoot.group()).doesNotContain("data-href");
    }
```

`LedgerRow` 생성자 인자 순서는 `(productId, productName, opening, initial, receive, returnQty, ship, adjust, countQty, closing)`이다.

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest'
```
Expected: `수불대장_상품행은_...` FAIL — `data-href`가 없다

- [ ] **Step 3: 템플릿 수정**

`admin/inventory-ledger.html`의 상품 행 `<tr th:each="row : ${ledger}">`를 아래로 교체:

```html
      <tr th:each="row : ${ledger}"
          th:data-href="@{/admin/inventory/transactions(productId=${row.productId},from=${from},to=${to})}">
```

`</main>` 바로 앞에 리스너를 넣는다(`returns.html`·`return-report.html`과 같은 스크립트):

```html
  <!-- 행 전체 클릭 → 그 상품·그 기간의 이동내역.
       셀 링크를 두지 않아 키보드로는 갈 길이 없다(반품 리포트와 같은 상태).
       필요해지면 두 화면의 tr에 tabindex와 Enter 핸들러를 함께 붙인다. -->
  <script>
    document.querySelector('main').addEventListener('click', function (e) {
      var tr = e.target.closest('tr[data-href]');
      if (!tr || e.target.closest('a')) return;
      if (window.getSelection().toString()) return;   // 텍스트 드래그 복사 중이면 이동하지 않는다
      location.href = tr.dataset.href;
    });
  </script>
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest'
```
Expected: PASS

- [ ] **Step 5: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 400건

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/templates/admin/inventory-ledger.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 수불대장 행을 눌러 그 상품의 이동을 연다

합계 30이 어느 발주였는지에서 화면이 끊겨 있었다. 개별 이동은 이미 이동내역에
있으므로 만들 것은 화면이 아니라 연결이다.

링크는 조회 중인 기간을 그대로 싣는다. 기간을 안 실으면 도착 화면이 전 기간을
보여줘서 방금 누른 행과 숫자가 맞지 않는다.

합계 행에는 붙이지 않는다 — 상품이 아니라 productId 없는 링크가 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 대조 줄 — 기초 + 이동 = 기말

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Modify: `src/main/resources/templates/admin/inventory-transactions.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`
- Test: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `ledgerRowOf(productId, from, to)`, Task 2의 모델 속성
- Produces: 모델 속성 `ledgerRow` (`LedgerRow` 또는 없음)

- [ ] **Step 1: 실패하는 테스트 작성 — 등식(서비스)**

이 기능의 핵심 주장이다. `InventoryServiceTest`에 추가:

```java
    // 이 기능이 성립한다는 것의 정의: 대조 줄의 '이동'과 같은 범위 트랜잭션의 변동 합이 같다.
    // 나머지가 다 통과해도 이게 깨지면 드릴다운이 요약과 다른 이야기를 하는 것이다.
    @Test
    void 대조줄의_이동과_범위_트랜잭션_변동합이_같다() {
        seedTxnAt(1L, InventoryTransactionType.OPENING, 100, LocalDateTime.of(2026, 8, 1, 0, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 20, LocalDateTime.of(2026, 9, 3, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.SHIP, -15, LocalDateTime.of(2026, 9, 11, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.RETURN, 3, LocalDateTime.of(2026, 9, 20, 10, 0));
        seedTxnAt(1L, InventoryTransactionType.RECEIVE, 99, LocalDateTime.of(2026, 10, 5, 10, 0));
        seedTxnAt(2L, InventoryTransactionType.RECEIVE, 77, LocalDateTime.of(2026, 9, 5, 10, 0));

        LocalDate from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30);
        var row = inventoryService.ledgerRowOf(1L, from, to).orElseThrow();
        int deltaSum = inventoryService.findTransactions(null, 1L, from, to, PageRequest.of(0, 100))
                .getContent().stream().mapToInt(InventoryTransaction::getDelta).sum();

        assertThat(row.closing() - row.opening()).isEqualTo(deltaSum);
        assertThat(deltaSum).isEqualTo(8);
        assertThat(row.opening()).isEqualTo(100);
        assertThat(row.closing()).isEqualTo(108);
    }
```

- [ ] **Step 2: 실패하는 테스트 작성 — 렌더(MVC)**

`WmsAdminControllerTest`에 추가:

```java
    @Test
    void 범위가_다_걸리면_대조줄을_렌더한다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(inventoryService.ledgerRowOf(eq(1L), any(), any())).thenReturn(java.util.Optional.of(
                new InventoryService.LedgerRow(1L, "상품 1", 100, 0, 20, 3, -15, 0, 0, 108)));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR"))
                        .param("productId", "1").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("기초").contains("100").contains("이동").contains("108");
    }

    // 범위가 없으면 기초·기말이 정의되지 않는다 — 빈 껍데기를 두지 않는다.
    @Test
    void 범위가_없으면_대조줄을_렌더하지_않고_조회도_하지_않는다() throws Exception {
        when(inventoryService.findTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        String html = mockMvc.perform(get("/admin/inventory/transactions")
                        .with(user("op").roles("OPERATOR")).param("productId", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("ledger-recon");
        verify(inventoryService, never()).ledgerRowOf(any(), any(), any());
    }
```

`eq`·`never` static import가 없으면 추가한다.

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest' --tests '*WmsAdminControllerTest'
```
Expected: 컴파일 실패 — `cannot find symbol: method ledgerRowOf` (Task 1에서 만들었다면 MVC 테스트가 렌더 부재로 FAIL)

- [ ] **Step 4: 컨트롤러에 대조 행 추가**

`inventoryTransactions`의 `model.addAttribute("to", to);` 다음에 넣는다:

```java
        // 셋이 다 있을 때만 기초·기말이 정의된다. 그때만 조회하고, 그때만 템플릿이 대조 줄을 낸다.
        if (productId != null && from != null && to != null)
            inventoryService.ledgerRowOf(productId, from, to)
                    .ifPresent(row -> model.addAttribute("ledgerRow", row));
```

- [ ] **Step 5: 템플릿에 대조 줄 추가**

`admin/inventory-transactions.html`의 범위 배지 `<p>` 바로 다음에 넣는다:

```html
  <!-- 드릴다운이 요약과 맞는지를 화면에서 확인한다. 아래 목록의 변동 합이 '이동'과 달라지면
       수불대장과 상세가 다른 이야기를 하는 것이고, 그건 두 숫자를 다 못 믿게 만든다.
       숫자는 수불대장을 만드는 buildLedger에서 그대로 나온다. -->
  <p th:if="${ledgerRow != null}" class="hint" id="ledger-recon">
    기초 <b th:text="${ledgerRow.opening}">100</b>
    + 이동 <b th:text="${ledgerRow.closing - ledgerRow.opening}">8</b>
    = 기말 <b th:text="${ledgerRow.closing}">108</b>
  </p>
```

- [ ] **Step 6: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest' --tests '*WmsAdminControllerTest'
```
Expected: PASS

- [ ] **Step 7: 등식 단언이 무는지 확인**

`InventoryService.findTransactions`의 상한을 `to.plusDays(1).atStartOfDay()`에서 `to.atStartOfDay()`로 잠시 바꾼다(반개구간을 깨뜨린다). 실행:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest'
```
Expected: `종료일_당일은_포함하고_다음날은_제외한다` FAIL. 확인 후 **되돌린다**.

- [ ] **Step 8: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 404건

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/resources/templates/admin/inventory-transactions.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java \
        src/test/java/com/jhg/wms/service/InventoryServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 드릴다운 화면에서 기초 + 이동 = 기말을 확인한다

드릴다운의 위험은 상세가 요약과 어긋나는 것이다. 어긋나면 어느 쪽이 틀렸는지
알 수 없어 두 숫자를 다 의심하게 되고, "이 수량이 왜 이렇게 됐는지 역추적할 수
있다"는 원장의 주장 자체가 깎인다. 그래서 등식을 화면에 띄운다 — 하단의
Σdelta == onHand 대조와 같은 성격이다.

숫자는 buildLedger에서 그대로 꺼낸다. 계산식을 새로 짜면 두 화면이 서로 다른
코드가 되어 언젠가 조용히 어긋나지만, 같은 함수에서 나오면 어긋날 수 없다.

범위가 다 걸렸을 때만 조회하고 렌더한다. 기초·기말은 기간 없이 정의되지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**스펙 커버리지**

| 스펙 절 | 태스크 |
|---------|--------|
| 상품·기간 필터 (`InventoryTransactionRepository`·`findTransactions`) | 1 |
| 반개구간 `[from, to+1일)` — `buildLedger`와 동일 | 1 (테스트로 고정) |
| `ledgerRowOf` — `buildLedger`에서 그대로 꺼낸다 | 1 |
| 행 클릭 링크, 합계 행 제외 | 3 |
| 범위 배지·범위 해제 | 2 |
| 유형 탭·페이징이 범위 유지 | 2 |
| 대조 줄, 범위 미지정 시 미렌더 | 4 |
| **대조 줄의 이동 == 트랜잭션 변동 합** | 4 (핵심 단언) |
| 경계: productId만 / 기간만 / 이동 0 / 없는 상품 | 1·4 |
| 비범위 8종 | 어느 태스크에도 없음 ✓ |

**타입 일관성**: `search(type, productId, from, to, pageable)`는 T1에서 정의되고 T1의 서비스만 부른다. `findTransactions(type, productId, from, to, pageable)`는 T1 정의, T2 컨트롤러 사용. `ledgerRowOf(productId, from, to) → Optional<LedgerRow>`는 T1 정의, T4 사용. `LedgerRow`는 기존 record이며 인자 순서를 T3·T4 테스트에 명시했다.

**플레이스홀더 없음**: 모든 코드 스텝에 완전한 코드, 모든 실행 스텝에 명령과 기대 출력.

**남은 판단 하나**: T1 Step 6에서 컨트롤러 호출부를 `(type, null, null, null, pageable)`로 임시 수정하는 것은 T1을 컴파일 가능하게 만들기 위한 최소 변경이고, T2가 곧바로 진짜 파라미터로 바꾼다. 리뷰어가 T1만 보면 "왜 null 세 개인가"로 보일 수 있어 여기 적어 둔다.
