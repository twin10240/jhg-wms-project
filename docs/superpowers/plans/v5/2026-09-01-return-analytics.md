# 반품 분석 데이터 계층 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코호트 반품률·범주 분포·사유 원문 조회를 내는 읽기 전용 서비스와 리포트 화면을 만든다.

**Architecture:** `ReturnAnalyticsService` 하나에 조회 메서드 셋. 분모(출고 코호트)를 만드는 private 메서드를 셋이 공유해 정의가 갈라지지 않게 한다. LLM 호출은 없다 — 해석은 2단계 MCP 클라이언트의 몫이다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Thymeleaf, JUnit 5, AssertJ, PostgreSQL 17

**설계 문서:** `docs/superpowers/specs/v5/2026-09-01-return-analytics-design.md`

## Global Constraints

- **읽기 전용이다.** 이 작업의 어떤 코드도 재고·반품·분류를 만들거나 고치지 않는다. 서비스는 `@Transactional(readOnly = true)`.
- **LLM 호출을 넣지 않는다.** 이 단계의 정의다. `ANTHROPIC_API_KEY`가 필요한 코드가 한 줄도 생기면 안 된다.
- 테스트 메서드 이름은 이 저장소 관례대로 한글이다. 주석도 한글이며 "왜"를 적는다.
- 커밋 메시지는 한글, `feat(wms):`/`test(wms):`/`docs(wms):` 형식, 본문에 판단 근거, 트레일러 2줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
  ```
- 테스트 실행 전제: PostgreSQL 17 기동(`brew services start postgresql@17`), DB `wms`/`wms_test`, 롤 `wms/wms`.
- 빌드 명령은 항상 `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home` 를 앞에 붙인다.
- **`README.md:16`의 테스트 수는 태스크마다 갱신한다.** 현재 373건이다. 373 → 377 → 380 → 382 → 384.
- 원장 엔티티는 `InventoryTransaction`이고 테이블명은 `inventory_adjustment`다(원장 도입 전 이름이 남아 있다). 코드에서는 엔티티 이름만 쓴다.

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/java/com/jhg/wms/domain/ReturnOwnerArea.java` | 반품 범주 → 소관 매핑 (도메인 판단) |
| `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java` | 코호트 집계·범주 분포·사유 원문 조회 |
| `src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java` | 기간 내 유형별 원장 행 조회 추가 |
| `src/main/java/com/jhg/wms/repository/RmaReturnRepository.java` | 주문 집합으로 반품 조회 추가 |
| `src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java` | 반품 집합으로 분류 조회 추가 |
| `src/main/java/com/jhg/wms/web/WmsAdminController.java` | `/admin/returns/report` 매핑 추가 |
| `src/main/resources/templates/admin/return-report.html` | 리포트 화면 |
| `src/main/resources/templates/fragments/layout.html` | 네비게이션 항목 추가 |
| `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java` | 집계 검증 |
| `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` | 화면 검증 (기존 파일에 추가) |

---

### Task 1: 코호트 반품률

**Files:**
- Create: `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java`
- Modify: `src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java`
- Modify: `src/main/java/com/jhg/wms/repository/RmaReturnRepository.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `record ReturnAnalyticsService.ProductReturnRate(Long productId, String productName, int shippedQty, int returnedQty, double returnRate)`
  - `record ReturnAnalyticsService.ReturnRateReport(LocalDate from, LocalDate to, int observedDays, List<ProductReturnRate> rows, int unlinkedShipRows)`
  - `ReturnRateReport productReturnRates(LocalDate from, LocalDate to)`
  - `List<InventoryTransaction> InventoryTransactionRepository.findByTypeInPeriod(InventoryTransactionType type, LocalDateTime from, LocalDateTime to)`
  - `List<RmaReturn> RmaReturnRepository.findByOrderIdInAndStatusNot(Collection<Long> orderIds, RmaStatus status)`

- [ ] **Step 1: 저장소에 조회를 추가한다**

`src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java` 의 마지막 메서드
(`sumDeltaByProductAndTypeInPeriod`) 아래, 닫는 `}` 바로 위에 넣는다.

```java

    // 코호트 분모용. 기존 sumDelta* 와 같은 반개구간([from, to))을 쓴다 — 경계를 다르게 하면
    // 같은 기간을 두 방식으로 세게 되어 수불대장과 리포트의 출고량이 어긋난다.
    @Query("SELECT t FROM InventoryTransaction t " +
           "WHERE t.type = :type AND t.createdAt >= :from AND t.createdAt < :to")
    List<InventoryTransaction> findByTypeInPeriod(@Param("type") InventoryTransactionType type,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);
```

`src/main/java/com/jhg/wms/repository/RmaReturnRepository.java` 의 `findByStatusOrderByIdDesc`
아래에 넣고, 파일 상단 import에 `java.util.Collection` 을 추가한다.

```java

    // 코호트 분자용. CANCELLED는 돌아오지 않은 반품이라 제외한다.
    @EntityGraph(attributePaths = "items")
    List<RmaReturn> findByOrderIdInAndStatusNot(Collection<Long> orderIds, RmaStatus status);
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReturnClassificationRepository;
import com.jhg.wms.repository.RmaReturnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 집계를 실제 PostgreSQL에서 검증한다. API 호출이 없으므로 전 구간이 공짜다.
 *
 * 원장 행의 createdAt은 InventoryTransaction.of()가 now()로 박는다. 과거 날짜 출고를
 * 만들려면 그 필드를 저장 전에 바꿔야 하는데, 이걸 위해 운영 코드에 setter를 여는 것은
 * 테스트 편의를 위해 도메인을 무르게 만드는 일이다. 리플렉션으로 테스트 안에서만 처리한다.
 */
@DataJpaTest
class ReturnAnalyticsServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired RmaReturnRepository rmaRepo;
    @Autowired ReturnClassificationRepository classificationRepo;

    ReturnAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new ReturnAnalyticsService(txnRepo, rmaRepo, classificationRepo, inventoryRepo);
    }

    private void 재고(Long productId, String name) {
        inventoryRepo.save(Inventory.create(productId, name, 100));
    }

    private void 출고(Long productId, int qty, String reference, LocalDate when) {
        InventoryTransaction t = InventoryTransaction.of(productId, InventoryTransactionType.SHIP,
                -qty, 100, 100 - qty, reference, null, "tester");
        ReflectionTestUtils.setField(t, "createdAt", when.atTime(12, 0));
        txnRepo.save(t);
    }

    private RmaReturn 반품(Long orderId, Long productId, int qty, String reason) {
        RmaReturn r = RmaReturn.create("RK-" + orderId + "-" + productId, orderId, reason);
        r.addItem(1L, productId, qty);
        return rmaRepo.save(r);
    }

    @Test
    void 기간_밖_출고는_분모에_들어가지_않는다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));   // 기간 안
        출고(1L, 90, "ORDER#101", LocalDate.of(2026, 2, 10));   // 기간 밖
        반품(100L, 1L, 2, "파손됐어요");

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).shippedQty()).isEqualTo(10);
        assertThat(report.rows().get(0).returnedQty()).isEqualTo(2);
        assertThat(report.rows().get(0).returnRate()).isEqualTo(0.2);
    }

    @Test
    void 취소된_반품은_분자에서_빠진다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        RmaReturn 취소됨 = 반품(100L, 1L, 5, "역시 안 보낼게요");
        취소됨.cancel();
        rmaRepo.save(취소됨);

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows().get(0).returnedQty()).isZero();
    }

    // 조용히 빠지면 분모가 줄어 반품률이 과대평가된다. 세어서 드러낸다.
    @Test
    void 주문_연결이_안_되는_출고행을_따로_센다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 40, "수동출고", LocalDate.of(2026, 3, 11));
        출고(1L, 50, null, LocalDate.of(2026, 3, 12));

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.unlinkedShipRows()).isEqualTo(2);
        assertThat(report.rows().get(0).shippedQty()).isEqualTo(10);
    }

    // 코호트의 성숙도는 기간의 성질이다. 경과 7일짜리 1%와 경과 60일짜리 1%는 다른 수다.
    @Test
    void 관찰_경과일은_기간_종료일부터_오늘까지다() {
        재고(1L, "상품 1");
        LocalDate 종료 = LocalDate.now().minusDays(10);
        출고(1L, 10, "ORDER#100", 종료.minusDays(1));

        var report = service.productReturnRates(종료.minusDays(30), 종료);

        assertThat(report.observedDays()).isEqualTo(10);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: 컴파일 실패 — `ReturnAnalyticsService` 심볼을 찾을 수 없다.

- [ ] **Step 4: `ReturnAnalyticsService`를 만든다**

`src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java`

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaReturnItem;
import com.jhg.wms.domain.RmaStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReturnClassificationRepository;
import com.jhg.wms.repository.RmaReturnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 반품 분석 조회. 읽기만 한다 — 재고·반품·분류를 만들지도 고치지도 않는다.
 *
 * LLM을 부르지 않는다. 사유 원문을 읽고 해석하는 일은 MCP 클라이언트의 모델이 한다.
 * 여기에 호출을 심으면 CLI 경로에서 안 돌고 같은 해석을 두 번 만들게 된다.
 */
@Service
@Transactional(readOnly = true)
public class ReturnAnalyticsService {

    /** 원장의 출고 행이 주문을 가리키는 형식. InventoryService가 이 형식으로 쓴다. */
    private static final Pattern ORDER_REF = Pattern.compile("^ORDER#(\\d+)$");

    private final InventoryTransactionRepository transactionRepository;
    private final RmaReturnRepository rmaReturnRepository;
    private final ReturnClassificationRepository classificationRepository;
    private final InventoryRepository inventoryRepository;

    public ReturnAnalyticsService(InventoryTransactionRepository transactionRepository,
                                  RmaReturnRepository rmaReturnRepository,
                                  ReturnClassificationRepository classificationRepository,
                                  InventoryRepository inventoryRepository) {
        this.transactionRepository = transactionRepository;
        this.rmaReturnRepository = rmaReturnRepository;
        this.classificationRepository = classificationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public record ProductReturnRate(Long productId, String productName,
                                    int shippedQty, int returnedQty, double returnRate) {}

    public record ReturnRateReport(LocalDate from, LocalDate to, int observedDays,
                                   List<ProductReturnRate> rows, int unlinkedShipRows) {}

    /**
     * 기간 내 출고 코호트. 분모·분자·원문 조회가 전부 이걸 통해야 정의가 갈라지지 않는다.
     *
     * reference 파싱이 실패한 행은 버리지 않고 센다. 조용히 빠지면 분모만 줄어
     * 반품률이 실제보다 나빠 보인다.
     */
    private record Cohort(Map<Long, Integer> shippedQtyByProduct, Set<Long> orderIds, int unlinkedShipRows) {}

    private Cohort cohort(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        List<InventoryTransaction> ships = transactionRepository.findByTypeInPeriod(
                InventoryTransactionType.SHIP, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        Set<Long> orderIds = new LinkedHashSet<>();
        int unlinked = 0;
        for (InventoryTransaction t : ships) {
            String ref = t.getReference();
            Matcher m = ref == null ? null : ORDER_REF.matcher(ref);
            if (m == null || !m.matches()) {
                unlinked++;
                continue;
            }
            orderIds.add(Long.valueOf(m.group(1)));
            // 출고 delta는 음수다. 수량은 절댓값을 쓴다.
            qtyByProduct.merge(t.getProductId(), Math.abs(t.getDelta()), Integer::sum);
        }
        return new Cohort(qtyByProduct, orderIds, unlinked);
    }

    private List<RmaReturn> cohortReturns(Cohort cohort) {
        // 빈 컬렉션으로 in 절을 만들면 Postgres가 문법 오류를 낸다.
        if (cohort.orderIds().isEmpty()) return List.of();
        return rmaReturnRepository.findByOrderIdInAndStatusNot(cohort.orderIds(), RmaStatus.CANCELLED);
    }

    public ReturnRateReport productReturnRates(LocalDate from, LocalDate to) {
        Cohort cohort = cohort(from, to);

        Map<Long, Integer> returnedByProduct = new HashMap<>();
        for (RmaReturn r : cohortReturns(cohort))
            for (RmaReturnItem i : r.getItems())
                // 분모에 없는 상품은 세지 않는다 — 분모가 없으면 비율이 아니다.
                if (cohort.shippedQtyByProduct().containsKey(i.getProductId()))
                    returnedByProduct.merge(i.getProductId(), i.getRequestedQuantity(), Integer::sum);

        Map<Long, String> names = new HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(cohort.shippedQtyByProduct().keySet()))
            names.put(inv.getProductId(), inv.getProductName());

        List<ProductReturnRate> rows = new ArrayList<>();
        cohort.shippedQtyByProduct().forEach((productId, shipped) -> {
            int returned = returnedByProduct.getOrDefault(productId, 0);
            rows.add(new ProductReturnRate(productId,
                    Objects.requireNonNullElse(names.get(productId), "(이름 없음)"),
                    shipped, returned, shipped == 0 ? 0 : (double) returned / shipped));
        });
        rows.sort(Comparator.comparingDouble(ProductReturnRate::returnRate)
                .thenComparingInt(ProductReturnRate::returnedQty).reversed());

        // 코호트가 아직 성숙하지 않았을 수 있다. 보정하지 않고 경과일을 그대로 낸다.
        long observedDays = Math.max(0, ChronoUnit.DAYS.between(to, LocalDate.now()));
        return new ReturnRateReport(from, to, (int) observedDays, rows, cohort.unlinkedShipRows());
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: `BUILD SUCCESSFUL`, 4건 통과.

- [ ] **Step 6: 변이 검증**

`cohort()`의 다음 줄을

```java
            if (m == null || !m.matches()) {
                unlinked++;
                continue;
            }
```

아래로 바꾸고 테스트를 돌린다.

```java
            if (m == null || !m.matches()) {
                continue;
            }
```

Expected: `주문_연결이_안_되는_출고행을_따로_센다() FAILED` (2 대신 0).
확인 후 원복하고 다시 돌려 4건 통과를 확인한다.

- [ ] **Step 7: README 테스트 수를 갱신한다**

`README.md:16`의 `373개`를 `377개`로 바꾼다(373 + 신규 4).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java \
        src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java \
        src/main/java/com/jhg/wms/repository/RmaReturnRepository.java \
        src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 코호트 기준 상품별 반품률

기간 내 출고된 주문을 모수로, 그 주문에서 나온 반품을 센다. 단순 비율
(기간 반품 ÷ 기간 출고)을 쓰지 않은 이유는 반품이 출고보다 늦게 오기 때문이다 —
출고가 늘면 분모만 먼저 커져 반품률이 가짜로 떨어진다. 성장하는 상품일수록
안전해 보이는, 정확히 반대로 읽히는 지표가 된다.

분자는 requestedQuantity다. acceptedQuantity는 검수가 끝나야 채워지므로
그걸 쓰면 접수·입고 단계 반품이 통째로 빠진다. 반품률은 "얼마나 돌아왔나"지
"얼마나 승인했나"가 아니다. 취소된 반품만 제외한다 — 돌아오지 않은 것이다.

reference가 ORDER#N 형식이 아닌 출고 행을 세어서 낸다. 조용히 버리면
분모만 줄어 반품률이 실제보다 나빠 보인다.

미성숙 코호트를 보정하지 않는다. 보정하려면 반품 도착 지연 분포를 가정해야
하는데 지금 데이터로는 세울 수 없다. 가정을 숨긴 보정값보다 경과일이 적힌
원값이 낫다.

검증: 377건 그린(373 + 신규 4). 변이 검증 — 파싱 실패 행을 세지 않게 바꾸면
해당 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

### Task 2: 범주 분포와 소관

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/ReturnOwnerArea.java`
- Modify: `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java`
- Modify: `src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: `ReturnAnalyticsService`의 `Cohort`·`cohortReturns` (Task 1, private)
- Produces:
  - `enum ReturnOwnerArea { PICKING, PACKAGING, PRODUCT_INFO, OUTSIDE }` — `String label()`, `static ReturnOwnerArea of(ReturnCategory)`
  - `record ReturnAnalyticsService.CategoryCount(ReturnCategory category, ReturnOwnerArea ownerArea, int count)`
  - `record ReturnAnalyticsService.CategoryBreakdown(List<CategoryCount> counts, int unclassified, int totalReturns)`
  - `CategoryBreakdown categoryBreakdown(LocalDate from, LocalDate to)`
  - `List<ReturnClassification> ReturnClassificationRepository.findByRmaReturnIdIn(Collection<Long> rmaReturnIds)`

- [ ] **Step 1: `ReturnOwnerArea`를 만든다**

`src/main/java/com/jhg/wms/domain/ReturnOwnerArea.java`

```java
package com.jhg.wms.domain;

/**
 * 반품 사유의 소관. 이 축이 보고서가 WMS에서 나와야 하는 이유다 —
 * 창고가 직접 통제할 수 있는 반품을 분리해내는 건 다른 시스템이 못 한다.
 *
 * 설정으로 빼지 않는다. 도메인 판단이지 취향이 아니다.
 *
 * "창고가 줄일 수 있나"를 boolean으로 넣지 않았다. DAMAGED는 포장 개선으로 줄 수도
 * 운송사 문제일 수도 있어 참·거짓 어느 쪽으로 접어도 총계가 거짓말을 한다.
 * 소관만 밝히고 판단은 읽는 사람에게 남긴다.
 */
public enum ReturnOwnerArea {

    PICKING("피킹·출고"),
    PACKAGING("포장·운송"),
    PRODUCT_INFO("상품 정보"),
    OUTSIDE("창고 밖");

    private final String label;

    ReturnOwnerArea(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ReturnOwnerArea of(ReturnCategory category) {
        return switch (category) {
            case WRONG_ITEM -> PICKING;
            case DAMAGED -> PACKAGING;
            case CHANGED_MIND -> PRODUCT_INFO;
            case OTHER -> OUTSIDE;
        };
    }
}
```

- [ ] **Step 2: 저장소에 조회를 추가한다**

`src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java` 를 통째로 아래로 바꾼다.

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.ReturnClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReturnClassificationRepository extends JpaRepository<ReturnClassification, Long> {

    Optional<ReturnClassification> findByRmaReturnId(Long rmaReturnId);

    boolean existsByRmaReturnId(Long rmaReturnId);

    // 코호트 반품들의 분류를 한 번에 읽는다(반품마다 조회하면 N+1이 된다).
    List<ReturnClassification> findByRmaReturnIdIn(Collection<Long> rmaReturnIds);
}
```

- [ ] **Step 3: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java` 의 마지막 테스트 아래,
클래스 닫는 `}` 바로 위에 넣는다. 파일 상단 import에 다음 넷을 추가한다.

```java
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.domain.ReturnOwnerArea;
import com.jhg.wms.domain.RmaDisposition;
```

```java

    private void 분류(Long rmaReturnId, ReturnCategory category) {
        classificationRepo.save(ReturnClassification.create(rmaReturnId, category, Confidence.HIGH,
                "근거", RmaDisposition.RESTOCKED, "claude-haiku-4-5-20251001", 100, 10));
    }

    @Test
    void 범주별_건수에_소관이_함께_나온다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(101L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.counts())
                .extracting(ReturnAnalyticsService.CategoryCount::category,
                            ReturnAnalyticsService.CategoryCount::ownerArea,
                            ReturnAnalyticsService.CategoryCount::count)
                .contains(org.assertj.core.groups.Tuple.tuple(
                                ReturnCategory.WRONG_ITEM, ReturnOwnerArea.PICKING, 1),
                          org.assertj.core.groups.Tuple.tuple(
                                ReturnCategory.DAMAGED, ReturnOwnerArea.PACKAGING, 1));
    }

    // 숨기면 합계가 안 맞고, 분류된 몇 건짜리 분포를 전체의 그림으로 읽게 된다.
    @Test
    void 분류가_없는_반품은_미분류로_따로_센다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.unclassified()).isEqualTo(1);
        assertThat(breakdown.totalReturns()).isEqualTo(2);
    }

    @Test
    void 코호트_밖_반품은_범주_분포에_들어가지_않는다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#900", LocalDate.of(2026, 1, 10));   // 기간 밖 출고
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(900L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.totalReturns()).isEqualTo(1);
    }
```

- [ ] **Step 4: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: 컴파일 실패 — `categoryBreakdown` 심볼을 찾을 수 없다.

- [ ] **Step 5: `categoryBreakdown`을 구현한다**

`ReturnAnalyticsService`의 `productReturnRates` 아래, 클래스 닫는 `}` 바로 위에 넣는다.
파일 상단 import에 다음 넷을 추가한다.

```java
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.domain.ReturnOwnerArea;
import java.util.EnumMap;
```

```java

    public record CategoryCount(ReturnCategory category, ReturnOwnerArea ownerArea, int count) {}

    public record CategoryBreakdown(List<CategoryCount> counts, int unclassified, int totalReturns) {}

    public CategoryBreakdown categoryBreakdown(LocalDate from, LocalDate to) {
        List<RmaReturn> returns = cohortReturns(cohort(from, to));
        Map<Long, ReturnCategory> categoryByReturn = categoriesOf(returns);

        Map<ReturnCategory, Integer> counts = new EnumMap<>(ReturnCategory.class);
        int unclassified = 0;
        for (RmaReturn r : returns) {
            ReturnCategory category = categoryByReturn.get(r.getId());
            if (category == null) unclassified++;
            else counts.merge(category, 1, Integer::sum);
        }

        // 0건인 범주도 행을 낸다. 빠진 범주와 0건인 범주는 읽는 사람에게 다른 뜻이다.
        List<CategoryCount> rows = new ArrayList<>();
        for (ReturnCategory category : ReturnCategory.values())
            rows.add(new CategoryCount(category, ReturnOwnerArea.of(category),
                    counts.getOrDefault(category, 0)));

        return new CategoryBreakdown(rows, unclassified, returns.size());
    }

    /** 반품 → 범주. 분류가 없는 반품은 키가 없다(미분류). */
    private Map<Long, ReturnCategory> categoriesOf(List<RmaReturn> returns) {
        if (returns.isEmpty()) return Map.of();
        List<Long> ids = returns.stream().map(RmaReturn::getId).toList();
        Map<Long, ReturnCategory> byReturn = new HashMap<>();
        for (ReturnClassification c : classificationRepository.findByRmaReturnIdIn(ids))
            byReturn.put(c.getRmaReturnId(), c.getCategory());
        return byReturn;
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: `BUILD SUCCESSFUL`, 7건 통과.

- [ ] **Step 7: 변이 검증**

`ReturnOwnerArea.of`의 `case WRONG_ITEM -> PICKING;` 을 `case WRONG_ITEM -> OUTSIDE;` 로
바꾸고 테스트를 돌린다.

Expected: `범주별_건수에_소관이_함께_나온다() FAILED`.
확인 후 원복하고 다시 돌려 7건 통과를 확인한다.

- [ ] **Step 8: README 테스트 수를 갱신한다**

`README.md:16`의 `377개`를 `380개`로 바꾼다(377 + 신규 3).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/ReturnOwnerArea.java \
        src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java \
        src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java \
        src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 반품 범주 분포와 소관

소관 축이 이 보고서가 WMS에서 나와야 하는 이유다. WRONG_ITEM은 피킹 오류라
창고 책임이고 CHANGED_MIND는 상품 정보 문제라 창고 밖이다 — 이 분리는 다른
시스템이 못 한다. 설정으로 빼지 않고 enum에 고정했다. 도메인 판단이지
취향이 아니기 때문이다.

"창고가 줄일 수 있나"를 boolean으로 넣지 않았다. DAMAGED는 포장 개선으로 줄
수도 운송사 문제일 수도 있어 참·거짓 어느 쪽으로 접어도 총계가 거짓말을 한다.
소관만 밝히고 판단은 읽는 사람에게 남긴다.

미분류를 따로 센다. 현재 반품 18건 중 분류가 붙은 것은 4건뿐이다. 숨기면
합계가 안 맞고, 4건짜리 분포를 18건의 그림으로 읽게 된다.

0건인 범주도 행을 낸다. 빠진 범주와 0건인 범주는 읽는 사람에게 다른 뜻이다.

검증: 380건 그린(377 + 신규 3). 변이 검증 — WRONG_ITEM의 소관을 바꾸면
해당 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

### Task 3: 사유 원문 묶음

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java`
- Modify: `README.md:16`

**Interfaces:**
- Consumes: `Cohort`·`cohortReturns`·`categoriesOf` (Task 1·2, private)
- Produces:
  - `record ReturnAnalyticsService.ReturnReasonEntry(Long rmaReturnId, Long orderId, String reason, ReturnCategory category, int requestedQuantity)` — `category`가 `null`이면 미분류
  - `List<ReturnReasonEntry> returnReasons(Long productId, LocalDate from, LocalDate to)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ReturnAnalyticsServiceTest`의 마지막 테스트 아래, 클래스 닫는 `}` 바로 위에 넣는다.

```java

    @Test
    void 상품의_사유_원문을_코호트_안에서만_모은다() {
        재고(1L, "상품 1");
        재고(2L, "상품 2");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(2L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#900", LocalDate.of(2026, 1, 10));   // 기간 밖
        반품(100L, 1L, 1, "뚜껑이 헐거워요");
        반품(101L, 2L, 1, "다른 상품이 왔어요");
        반품(900L, 1L, 1, "기간 밖 반품");

        var entries = service.returnReasons(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).reason()).isEqualTo("뚜껑이 헐거워요");
        assertThat(entries.get(0).orderId()).isEqualTo(100L);
    }

    // 미분류를 빼면 원문 묶음이 분류된 것만 남아, 읽는 쪽이 전체를 봤다고 착각한다.
    @Test
    void 미분류_반품도_원문에_포함되고_범주는_비어_있다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var entries = service.returnReasons(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.reason()).isEqualTo("그냥요");
            assertThat(e.category()).isNull();
        });
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: 컴파일 실패 — `returnReasons` 심볼을 찾을 수 없다.

- [ ] **Step 3: `returnReasons`를 구현한다**

`ReturnAnalyticsService`의 `categoriesOf` 위, `categoryBreakdown` 아래에 넣는다.

```java

    /**
     * 상품 하나의 반품 사유 원문. 이 단계의 화면은 쓰지 않는다 — 2단계에서 MCP 도구가
     * 부를 조회다. 지금 넣는 이유는 집계와 원문 조회가 같은 코호트 정의를 공유해야 하기
     * 때문이다. 나중에 따로 만들면 두 정의가 갈라진다.
     */
    public record ReturnReasonEntry(Long rmaReturnId, Long orderId, String reason,
                                    ReturnCategory category, int requestedQuantity) {}

    public List<ReturnReasonEntry> returnReasons(Long productId, LocalDate from, LocalDate to) {
        List<RmaReturn> returns = cohortReturns(cohort(from, to));
        Map<Long, ReturnCategory> categoryByReturn = categoriesOf(returns);

        List<ReturnReasonEntry> entries = new ArrayList<>();
        for (RmaReturn r : returns)
            for (RmaReturnItem i : r.getItems())
                if (i.getProductId().equals(productId))
                    entries.add(new ReturnReasonEntry(r.getId(), r.getOrderId(), r.getReason(),
                            categoryByReturn.get(r.getId()), i.getRequestedQuantity()));
        return entries;
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnAnalyticsServiceTest*'
```
Expected: `BUILD SUCCESSFUL`, 9건 통과.

- [ ] **Step 5: 변이 검증**

`returnReasons`의 `if (i.getProductId().equals(productId))` 를
`if (categoryByReturn.get(r.getId()) != null && i.getProductId().equals(productId))` 로
바꾸고 테스트를 돌린다.

Expected: `미분류_반품도_원문에_포함되고_범주는_비어_있다() FAILED` (2 대신 1).
확인 후 원복하고 다시 돌려 9건 통과를 확인한다.

- [ ] **Step 6: README 테스트 수를 갱신한다**

`README.md:16`의 `380개`를 `382개`로 바꾼다(380 + 신규 2).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/ReturnAnalyticsService.java \
        src/test/java/com/jhg/wms/service/ReturnAnalyticsServiceTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 상품별 반품 사유 원문 조회

이 단계의 화면은 쓰지 않는다. 2단계에서 MCP 도구가 부를 조회이고, 지금 넣는
이유는 집계와 원문 조회가 같은 코호트 정의를 공유해야 하기 때문이다.
나중에 따로 만들면 "이 상품 반품 8건"과 "읽어본 사유 5건"이 어긋나고,
어느 쪽이 맞는지 아무도 모르게 된다.

미분류 반품도 포함하고 category를 null로 남긴다. 빼면 원문 묶음이 분류된
것만 남아, 읽는 쪽이 전체를 봤다고 착각한다. 분류는 V4.0 이후 반품에만
붙어 있다.

검증: 382건 그린(380 + 신규 2). 변이 검증 — 미분류를 걸러내면 해당
테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

### Task 4: 리포트 화면

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Create: `src/main/resources/templates/admin/return-report.html`
- Modify: `src/main/resources/templates/fragments/layout.html:21`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` (기존 파일에 추가)
- Modify: `README.md` (테스트 수 · 관리자 UI 표)

**Interfaces:**
- Consumes: `productReturnRates`, `categoryBreakdown` (Task 1·2)
- Produces: `GET /admin/returns/report?from=&to=` — 모델 속성 `report`·`breakdown`·`from`·`to`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`에 추가한다. 새 파일을
만들지 않는다 — 이 저장소의 관리자 화면 테스트는 `@WebMvcTest(WmsAdminController.class)`
슬라이스 하나를 공유하고, 새 `@SpringBootTest`를 만들면 컨텍스트가 하나 더 살아난다.

파일 상단 import에 둘을 추가한다.

```java
import com.jhg.wms.service.ReturnAnalyticsService;
import java.time.LocalDate;
```

그리고 정적 import에 하나를 추가한다.

```java
import static org.mockito.ArgumentMatchers.any;
```

`@MockitoBean` 필드 목록(`cycleCountService` 아래)에 한 줄을 넣는다.

```java
    @MockitoBean ReturnAnalyticsService returnAnalyticsService;
```

클래스 마지막 테스트 아래, 닫는 `}` 바로 위에 두 테스트를 넣는다.

```java

    @Test
    void 반품리포트_화면이_반품률과_범주_분포를_렌더링한다() throws Exception {
        var report = new ReturnAnalyticsService.ReturnRateReport(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 5,
                List.of(new ReturnAnalyticsService.ProductReturnRate(1L, "상품 1", 100, 8, 0.08)), 2);
        var breakdown = new ReturnAnalyticsService.CategoryBreakdown(
                List.of(new ReturnAnalyticsService.CategoryCount(
                        ReturnCategory.WRONG_ITEM, ReturnOwnerArea.PICKING, 3)), 1, 4);
        when(returnAnalyticsService.productReturnRates(any(), any())).thenReturn(report);
        when(returnAnalyticsService.categoryBreakdown(any(), any())).thenReturn(breakdown);

        mockMvc.perform(get("/admin/returns/report").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/return-report"))
                .andExpect(content().string(allOf(
                        containsString("8.0%"),           // 반품률이 퍼센트로 렌더링된다
                        containsString("피킹·출고"),        // 소관 라벨이 붙는다
                        containsString("미분류"))));        // 숨기지 않는다
    }

    // 기간을 매번 손으로 넣게 하면 아무도 안 본다. 기본값이 있어야 링크 한 번으로 열린다.
    @Test
    void 반품리포트_기간을_안_주면_최근_30일이_기본이다() throws Exception {
        when(returnAnalyticsService.productReturnRates(any(), any())).thenReturn(
                new ReturnAnalyticsService.ReturnRateReport(
                        LocalDate.now().minusDays(30), LocalDate.now(), 0, List.of(), 0));
        when(returnAnalyticsService.categoryBreakdown(any(), any())).thenReturn(
                new ReturnAnalyticsService.CategoryBreakdown(List.of(), 0, 0));

        mockMvc.perform(get("/admin/returns/report").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk());

        verify(returnAnalyticsService).productReturnRates(LocalDate.now().minusDays(30), LocalDate.now());
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'
```
Expected: 새 테스트 2건 실패 — `/admin/returns/report`가 404다(`status().isOk()` 위반).

- [ ] **Step 3: 컨트롤러에 매핑을 추가한다**

`src/main/java/com/jhg/wms/web/WmsAdminController.java` 의 `ledger` 메서드 아래에 넣는다.
이 클래스는 `@RequiredArgsConstructor`를 쓰므로 `private final` 필드를 하나 더 선언하면
주입이 따라온다. `private final CycleCountService cycleCountService;` 아래에 넣는다.

```java
    private final ReturnAnalyticsService returnAnalyticsService;
```

파일 상단 import에 `com.jhg.wms.service.ReturnAnalyticsService` 를 추가한다.

```java

    @GetMapping("/admin/returns/report")
    public String returnReport(@RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to,
                               Model model) {
        // 기간을 매번 손으로 넣게 하면 아무도 안 본다. 링크 한 번으로 열려야 한다.
        if (to == null) to = LocalDate.now();
        if (from == null) from = to.minusDays(30);
        try {
            model.addAttribute("report", returnAnalyticsService.productReturnRates(from, to));
            model.addAttribute("breakdown", returnAnalyticsService.categoryBreakdown(from, to));
        } catch (IllegalArgumentException e) {
            model.addAttribute("report", null);
            model.addAttribute("breakdown", null);
            model.addAttribute("errorMessage", e.getMessage());
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/return-report";
    }
```

- [ ] **Step 4: 템플릿을 만든다**

`src/main/resources/templates/admin/return-report.html`

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 반품 리포트')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('returnreport')}"></nav>
<main>
  <h2>반품 리포트</h2>

  <div th:replace="~{fragments/layout :: flash}"></div>

  <form class="form-row" method="get" th:action="@{/admin/returns/report}">
    <label>시작일 <input type="date" name="from" th:value="${from}" /></label>
    <label>종료일 <input type="date" name="to" th:value="${to}" /></label>
    <button type="submit">조회</button>
  </form>

  <div th:if="${report != null}">
    <p>
      기간 내 출고된 주문을 모수로 셉니다.
      <strong th:text="'관찰 경과: 기간 종료일로부터 ' + ${report.observedDays} + '일'">관찰 경과</strong>
      — 경과가 짧으면 반품이 아직 들어오는 중이라 반품률이 실제보다 낮게 보입니다.
    </p>
    <p th:if="${report.unlinkedShipRows > 0}">
      주문 연결 불가 출고 <strong th:text="${report.unlinkedShipRows}">0</strong>건은 분모에서 빠졌습니다.
    </p>

    <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>상품 ID</th><th>상품명</th>
          <th style="text-align:right">출고</th>
          <th style="text-align:right">반품</th>
          <th style="text-align:right">반품률</th>
        </tr>
      </thead>
      <tbody>
        <tr th:each="row : ${report.rows}">
          <td th:text="${row.productId}">1</td>
          <td th:text="${row.productName}">상품 1</td>
          <td style="text-align:right" th:text="${row.shippedQty}">100</td>
          <td style="text-align:right" th:text="${row.returnedQty}">8</td>
          <td style="text-align:right"
              th:text="${#numbers.formatDecimal(row.returnRate * 100, 1, 1)} + '%'">8.0%</td>
        </tr>
        <tr th:if="${report.rows.isEmpty()}">
          <td colspan="5">기간 내 주문과 연결된 출고가 없습니다.</td>
        </tr>
      </tbody>
    </table>
    </div>
  </div>

  <div th:if="${breakdown != null}">
    <h3>반품 사유 범주</h3>
    <p>
      분류된 반품만 범주로 나뉩니다. 미분류
      <strong th:text="${breakdown.unclassified}">0</strong>건 /
      전체 <strong th:text="${breakdown.totalReturns}">0</strong>건.
    </p>
    <div class="table-wrap">
    <table>
      <thead>
        <tr><th>범주</th><th>소관</th><th style="text-align:right">건수</th></tr>
      </thead>
      <tbody>
        <tr th:each="c : ${breakdown.counts}">
          <td th:text="${c.category}">DAMAGED</td>
          <td th:text="${c.ownerArea.label()}">포장·운송</td>
          <td style="text-align:right" th:text="${c.count}">0</td>
        </tr>
      </tbody>
    </table>
    </div>
  </div>
</main>
</body>
</html>
```

- [ ] **Step 5: 네비게이션에 항목을 넣는다**

`src/main/resources/templates/fragments/layout.html:21` 의 반품 링크 아래에 한 줄을 넣는다.

```html
    <a th:href="@{/admin/returns/report}" th:classappend="${active == 'returnreport'} ? 'active'">반품 리포트</a>
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'
```
Expected: `BUILD SUCCESSFUL`. `WmsAdminControllerTest` 전체가 그린이고 새 테스트 2건이 포함된다.

- [ ] **Step 7: 변이 검증**

컨트롤러의 `if (from == null) from = to.minusDays(30);` 을
`if (from == null) from = to.minusDays(7);` 로 바꾸고 테스트를 돌린다.

Expected: `반품리포트_기간을_안_주면_최근_30일이_기본이다() FAILED`.
확인 후 원복하고 다시 돌려 그린을 확인한다.

- [ ] **Step 8: 전체 스위트를 돌린다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew build --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`, 384건 그린. `ClassificationEvalTest`는 실행되지 않는다(태그 제외).

- [ ] **Step 9: README를 갱신한다**

두 곳을 고친다.

1. `README.md:16`의 `382개`를 `384개`로 바꾼다(382 + 신규 2).
2. 관리자 UI 표에서 `/admin/returns/{id}` 행 아래에 한 줄을 넣는다.

```markdown
| `/admin/returns/report` | 반품 리포트 — 코호트 반품률(기간 내 출고 대비), 범주별 분포와 소관, 미분류 건수 | 인증 |
```

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/resources/templates/admin/return-report.html \
        src/main/resources/templates/fragments/layout.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java \
        README.md
git commit -F - <<'EOF'
feat(wms): 반품 리포트 화면

숫자만 낸다. 해석은 2단계에서 MCP 클라이언트의 모델이 한다.

화면이 숨기지 않는 것 셋을 그대로 노출한다 — 관찰 경과일(코호트가 아직
성숙하지 않았을 수 있다), 주문 연결 불가 출고 건수(분모에서 빠졌다),
미분류 반품 수(범주 분포의 분모가 전체와 다르다). 셋 다 숨기면 표가
깔끔해지는 대신 읽는 사람이 틀린 결론에 이른다.

기본 기간을 최근 30일로 둔다. 기간을 매번 손으로 넣게 하면 아무도 안 본다.

검증: 384건 그린(382 + 신규 2). 변이 검증 — 기본 기간을 7일로 바꾸면
해당 테스트가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TWs4wJCZaMc3M7iFMT1utA
EOF
```

---

## 완료 조건

- `./gradlew build` 384건 그린, `ClassificationEvalTest`는 실행되지 않는다
- `ReturnAnalyticsService`에 LLM 호출이 없다 — `anthropic` import가 한 줄도 없다
- `/admin/returns/report`가 반품률·범주 분포를 내고, 관찰 경과일·연결 불가 출고·미분류를 화면에 드러낸다
- `README.md:16` 테스트 수가 384, 관리자 UI 표에 리포트 화면 행이 있다
- `.superpowers/sdd/progress.md`에 판단 근거 append
