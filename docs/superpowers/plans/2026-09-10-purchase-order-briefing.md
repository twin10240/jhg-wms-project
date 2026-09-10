# 발주 브리핑 생성과 그 평가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 발주 화면에서 버튼 한 번으로 근거 패널 상위 5개를 읽은 브리핑 문단을 생성·저장하고, 그 생성물을 기계 채점(숫자 대조)과 LLM-as-judge로 재는 평가 하네스를 세운다.

**Architecture:** 기능은 기존 두 분류(반품·발주 메모)의 틀을 그대로 따르되 **동기**다 — 사용자가 버튼을 누르고 결과를 기다리는 읽기 시점이라 비동기 트리거를 못 쓴다. 평가는 채점을 둘로 나눈다: 숫자 대조는 모델 없이 돌고(무료·확정적), judge는 기계가 못 재는 것만 본다. 집계는 `EvalAggregator`를 그대로 재사용한다 — 체크리스트 항목 하나는 값이 `YES`/`NO` 둘뿐인 분류이기 때문이다.

**Tech Stack:** Spring Boot 3 · JPA(ddl-auto update) · Thymeleaf · Anthropic Java SDK · JUnit 5 · AssertJ · PostgreSQL 17

## Global Constraints

- 설계 정본: `docs/superpowers/specs/v11/2026-09-10-purchase-order-briefing-design.md`. 판단이 갈리면 스펙이 이긴다.
- 작업 규약: `feat/...` 브랜치 → PR 머지. 커밋 메시지는 한글, `feat(wms):`/`test(wms):`/`docs(wms):`, 본문에 "왜 이 선택인가"를 적고 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 트레일러를 붙인다.
- 판단 근거는 `.superpowers/sdd/progress.md` 원장에 append. **거부한 설계와 그 이유까지 남긴다.**
- 테스트 실행: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test` — 실제 PostgreSQL 17이 떠 있어야 한다(`brew services start postgresql@17`, DB `wms`/`wms_test`, 롤 `wms/wms`).
- **현재 기준선: Java 518건 그린.** 각 태스크 끝에서 이 수가 줄면 안 된다.
- 평가 실행은 `@Tag("eval")`이라 기본 `test`에서 빠지고 `./gradlew evalTest`로만 돈다. `ANTHROPIC_API_KEY` 필요.
- 스키마는 `ddl-auto: update`다(Flyway 미도입). **새 테이블은 마이그레이션 SQL이 필요 없다.** 다만 prod(Railway)에는 기존 enum 마이그레이션이 아직 안 돌았으므로, 이 작업의 배포는 그것 뒤다.
- 브리핑은 기존 두 AI 기능과 같이 **참고 표시 전용**이다. 발주 내용에 영향을 주지 않는다.
- **1부(Task 1~4)와 2부(Task 5~9)를 따로 검증하고 따로 원장에 적는다.** V10.0은 두 기능을 한 브랜치에 실었다가 그 구간만 리뷰·실기동 기록이 비었다.

---

# 1부 — 기능

## Task 1: 스냅샷 타입과 브리핑 엔티티

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/BriefingSnapshot.java`
- Create: `src/main/java/com/jhg/wms/domain/PurchaseOrderBriefing.java`
- Create: `src/main/java/com/jhg/wms/repository/PurchaseOrderBriefingRepository.java`
- Test: `src/test/java/com/jhg/wms/domain/BriefingSnapshotTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderAdviceService.ProductAdvice`(기존 record — `productId, productName, shippedQty, sampleDays, dailyAverage, onHandQty, reservedQty, availableQty, daysToStockout, lastOrder`), `PurchaseOrderAdviceService.LastOrder`(`purchaseOrderId, status, orderedOn, quantity, receivedOn, leadTimeDays`)
- Produces: `BriefingSnapshot.of(LocalDate, List<ProductAdvice>, int topN)` → `BriefingSnapshot`; `BriefingSnapshot.rows()` → `List<BriefingSnapshot.Row>`; `PurchaseOrderBriefing.create(String body, String inputSnapshot, String model, int inputTokens, int outputTokens)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/domain/BriefingSnapshotTest.java`:

```java
package com.jhg.wms.domain;

import com.jhg.wms.service.PurchaseOrderAdviceService.LastOrder;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingSnapshotTest {

    private ProductAdvice advice(long id, String name, Double daysToStockout) {
        return new ProductAdvice(id, name, 60, 30L, 2.0, 20, 5, 15, daysToStockout,
                new LastOrder(900L, PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 9, 1), 50,
                        LocalDate.of(2026, 9, 3), 2L));
    }

    @Test
    void 상위_N개만_담는다() {
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10),
                List.of(advice(1, "A", 1.0), advice(2, "B", 2.0), advice(3, "C", 3.0)), 2);

        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows()).extracting(BriefingSnapshot.Row::productId).containsExactly(1L, 2L);
    }

    // 근거 패널은 이미 소진 임박 순으로 정렬해서 준다. 여기서 다시 정렬하지 않는다 —
    // 정렬 규칙이 두 군데 있으면 갈라진다.
    @Test
    void 받은_순서를_그대로_쓴다() {
        var snapshot = BriefingSnapshot.of(LocalDate.of(2026, 9, 10),
                List.of(advice(3, "C", 3.0), advice(1, "A", 1.0)), 5);

        assertThat(snapshot.rows()).extracting(BriefingSnapshot.Row::productId).containsExactly(3L, 1L);
    }

    // 직전 발주가 없는 상품이 있다. null을 그대로 담아야 프롬프트가 "없음"으로 렌더한다.
    @Test
    void 직전_발주가_없으면_null이다() {
        var noLast = new ProductAdvice(7L, "G", 0, 5L, 0.0, 3, 0, 3, null, null);

        var row = BriefingSnapshot.of(LocalDate.of(2026, 9, 10), List.of(noLast), 5).rows().get(0);

        assertThat(row.lastOrderId()).isNull();
        assertThat(row.lastOrderedOn()).isNull();
        assertThat(row.lastOrderQty()).isNull();
        assertThat(row.daysToStockout()).isNull();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingSnapshotTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class BriefingSnapshot`

- [ ] **Step 3: 스냅샷 타입을 만든다**

`src/main/java/com/jhg/wms/domain/BriefingSnapshot.java`:

```java
package com.jhg.wms.domain;

import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;

import java.time.LocalDate;
import java.util.List;

/**
 * 브리핑 생성에 쓴 입력. 모델에게 준 값이자 <b>채점의 근거 집합</b>이다.
 *
 * <p>이 타입을 저장하는 이유가 설계의 핵심이다. 재고는 계속 변하므로 브리핑을 나중에 열면
 * 화면의 패널 값은 이미 다른 값이다. 스냅샷이 없으면 "이 문장의 숫자가 맞았나"를 영영
 * 판정할 수 없고, 숫자 대조 채점기가 먹을 것이 없어 평가 자체가 성립하지 않는다.
 *
 * <p>{@link ProductAdvice}를 그대로 저장하지 않고 좁힌다 — onHand·reserved는 브리핑이
 * 쓸 값이 아니라 available만 있으면 되고, 근거 집합이 넓어지면 환각이 우연히 통과한다.
 */
public record BriefingSnapshot(LocalDate generatedOn, List<Row> rows) {

    /**
     * @param daysToStockout 일평균이 0이면 null이다 — 0일이 아니라 잴 것이 없다는 뜻이다.
     *                       {@link ProductAdvice}의 규약을 그대로 잇는다.
     */
    public record Row(Long productId, String productName,
                      int shippedQty, long sampleDays, double dailyAverage,
                      int availableQty, Double daysToStockout,
                      Long lastOrderId, LocalDate lastOrderedOn, Integer lastOrderQty) {}

    /** 근거 패널이 이미 소진 임박 순으로 정렬해 주므로 여기서 다시 정렬하지 않는다. */
    public static BriefingSnapshot of(LocalDate generatedOn, List<ProductAdvice> advice, int topN) {
        List<Row> rows = advice.stream().limit(topN).map(a -> new Row(
                a.productId(), a.productName(),
                a.shippedQty(), a.sampleDays(), a.dailyAverage(),
                a.availableQty(), a.daysToStockout(),
                a.lastOrder() == null ? null : a.lastOrder().purchaseOrderId(),
                a.lastOrder() == null ? null : a.lastOrder().orderedOn(),
                a.lastOrder() == null ? null : a.lastOrder().quantity())).toList();
        return new BriefingSnapshot(generatedOn, rows);
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingSnapshotTest*'`
Expected: PASS (3건)

- [ ] **Step 5: 엔티티와 리포지토리를 만든다**

`src/main/java/com/jhg/wms/domain/PurchaseOrderBriefing.java`:

```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 생성된 발주 브리핑. {@link PurchaseOrderMemoClassification}과 같은 이유로 도메인에서
 * 떼어낸 별도 테이블이다 — 브리핑은 발주의 상태가 아니라 참고 정보이고, 도메인에 섞으면
 * "이 필드가 업무 규칙인가 힌트인가"가 흐려진다.
 *
 * <p>분류 둘과 달리 특정 발주에 매이지 않는다. 브리핑은 발주 하나가 아니라 화면 전체를
 * 읽은 것이라 {@code purchaseOrderId}가 없다.
 */
@Entity
@Table(name = "purchase_order_briefing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderBriefing {

    @Id @GeneratedValue
    @Column(name = "purchase_order_briefing_id")
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 그때 모델에게 준 패널 값(JSON). 채점의 근거 집합이라 본문과 반드시 같이 산다. */
    @Column(nullable = false, columnDefinition = "text")
    private String inputSnapshot;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private Instant createdAt;

    public static PurchaseOrderBriefing create(String body, String inputSnapshot,
                                               String model, int inputTokens, int outputTokens) {
        PurchaseOrderBriefing b = new PurchaseOrderBriefing();
        b.body = body;
        b.inputSnapshot = inputSnapshot;
        b.model = model;
        b.inputTokens = inputTokens;
        b.outputTokens = outputTokens;
        b.createdAt = Instant.now();
        return b;
    }
}
```

`src/main/java/com/jhg/wms/repository/PurchaseOrderBriefingRepository.java`:

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrderBriefing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PurchaseOrderBriefingRepository extends JpaRepository<PurchaseOrderBriefing, Long> {

    /** 화면은 마지막 것 하나만 보여준다. 이력은 평가와 사후 확인용으로 남는다. */
    Optional<PurchaseOrderBriefing> findFirstByOrderByCreatedAtDesc();
}
```

- [ ] **Step 6: 전체 테스트로 회귀를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, 521건 (기준선 518 + 이번 3건)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/BriefingSnapshot.java \
        src/main/java/com/jhg/wms/domain/PurchaseOrderBriefing.java \
        src/main/java/com/jhg/wms/repository/PurchaseOrderBriefingRepository.java \
        src/test/java/com/jhg/wms/domain/BriefingSnapshotTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 브리핑 스냅샷 타입과 저장 엔티티를 만든다

스냅샷을 저장하는 것이 이 설계의 핵심이다. 재고는 계속 변하므로 브리핑을 나중에 열면 화면의
패널 값은 이미 다른 값이고, 스냅샷이 없으면 "이 문장의 숫자가 맞았나"를 영영 판정할 수 없다.
숫자 대조 채점기가 먹을 것도 이것이라 없으면 평가가 성립하지 않는다.

ProductAdvice를 그대로 저장하지 않고 좁혔다. onHand·reserved는 브리핑이 쓸 값이 아니고,
근거 집합이 넓어지면 환각이 우연히 통과한다.

엔티티는 PurchaseOrderMemoClassification과 같은 이유로 도메인에서 뗐다. 다만 분류 둘과 달리
purchaseOrderId가 없다 — 브리핑은 발주 하나가 아니라 화면 전체를 읽은 것이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 생성기 인터페이스·프롬프트·Claude 구현

**Files:**
- Create: `src/main/java/com/jhg/wms/service/PurchaseOrderBriefingGenerator.java`
- Create: `src/main/resources/prompts/purchase-order-briefing.txt`
- Create: `src/main/java/com/jhg/wms/client/ClaudePurchaseOrderBriefingGenerator.java`
- Modify: `src/main/java/com/jhg/wms/config/AiConfig.java` (빈 추가)
- Modify: `src/main/resources/application.yml:65-71` (브리핑 전용 설정 추가)
- Test: `src/test/java/com/jhg/wms/client/BriefingPromptRenderTest.java`

**Interfaces:**
- Consumes: `BriefingSnapshot`(Task 1)
- Produces: `PurchaseOrderBriefingGenerator.generate(BriefingSnapshot)` → `Optional<Briefing>`; `record Briefing(String body, String model, int inputTokens, int outputTokens)`; `ClaudePurchaseOrderBriefingGenerator.renderInput(BriefingSnapshot)` → `String` (package-private, 테스트가 부른다)

- [ ] **Step 1: 인터페이스를 만든다**

`src/main/java/com/jhg/wms/service/PurchaseOrderBriefingGenerator.java`:

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.BriefingSnapshot;

import java.util.Optional;

/**
 * 발주 브리핑 생성기. 인터페이스를 두는 이유는 {@link PurchaseOrderMemoClassifier}와 같다 —
 * 서비스가 특정 SDK에 묶이지 않게, 그리고 테스트가 실제 API를 호출하지 않아도 되게.
 * 실패(타임아웃·빈 응답·키 미설정)는 전부 empty다. 예외로 알리지 않는다.
 */
public interface PurchaseOrderBriefingGenerator {

    Optional<Briefing> generate(BriefingSnapshot snapshot);

    record Briefing(String body, String model, int inputTokens, int outputTokens) {}
}
```

- [ ] **Step 2: 프롬프트를 쓴다**

`src/main/resources/prompts/purchase-order-briefing.txt`:

```
너는 물류창고의 발주 담당자를 돕는 조수다. 아래 표는 소진이 임박한 순으로 고른 상품들의
실측치다. 이걸 읽고 담당자가 지금 무엇을 발주할지 판단하는 데 쓸 짧은 브리핑을 쓴다.

지켜야 할 것:

- 숫자는 표에 있는 값만 쓴다. 더하거나 나누거나 평균 내지 않는다.
  표에 없는 수치가 필요하면 그 말을 쓰지 말고 넘어간다.
- 표에 없는 사실을 지어내지 않는다. 거래처 사정·시즌·행사·리드타임 변화는 이 표에 없다.
- 지금 발주할 상품을 고르고, 각각 왜 급한지 표의 값으로 근거를 댄다.
- 소진 예상이 "없음"인 상품은 출고가 없어서 잴 것이 없다는 뜻이다. 오늘 소진된다는 뜻이 아니다.
- 발주 수량은 제안하지 않는다. 그건 표에 근거가 없다.
- 5문장 이내. 목록이 아니라 문단으로 쓴다.

발주를 급하게 할 이유가 표에 안 보이면 그렇게 쓴다. 억지로 고르지 않는다.
```

- [ ] **Step 3: 입력 렌더 테스트를 쓴다(실패 확인용)**

`src/test/java/com/jhg/wms/client/BriefingPromptRenderTest.java`:

```java
package com.jhg.wms.client;

import com.jhg.wms.domain.BriefingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델에게 실제로 무엇이 가는지 고정한다. API 호출 없이 도는 순수 함수라 공짜로 확인된다.
 */
class BriefingPromptRenderTest {

    private BriefingSnapshot.Row row(long id, String name, Double days, Long lastId) {
        return new BriefingSnapshot.Row(id, name, 60, 30L, 2.0, 15, days,
                lastId, lastId == null ? null : LocalDate.of(2026, 9, 1), lastId == null ? null : 50);
    }

    @Test
    void 소진_예상이_null이면_없음으로_쓴다() {
        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(row(1, "볼펜", null, 900L))));

        assertThat(rendered).contains("소진 예상: 없음");
        assertThat(rendered).doesNotContain("소진 예상: 0");
    }

    @Test
    void 직전_발주가_null이면_없음으로_쓴다() {
        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(row(2, "노트", 3.5, null))));

        assertThat(rendered).contains("직전 발주: 없음");
        assertThat(rendered).doesNotContain("null");
    }

    // 일평균은 소수 1자리로 고정한다. 렌더가 3.2를 주면 채점기의 근거 집합도 3.2 기준이 된다 —
    // 여기와 채점기가 어긋나면 정상 인용이 환각으로 잡힌다.
    @Test
    void 일평균은_소수_한_자리로_쓴다() {
        var r = new BriefingSnapshot.Row(3L, "테이프", 97, 30L, 3.2333, 10, 3.09, null, null, null);

        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(r)));

        assertThat(rendered).contains("일평균: 3.2");
        assertThat(rendered).doesNotContain("3.2333");
    }
}
```

- [ ] **Step 4: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingPromptRenderTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class ClaudePurchaseOrderBriefingGenerator`

- [ ] **Step 5: Claude 구현을 만든다**

`src/main/java/com/jhg/wms/client/ClaudePurchaseOrderBriefingGenerator.java`:

```java
package com.jhg.wms.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.service.PurchaseOrderBriefingGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 분류 둘과 달리 <b>구조화 출력을 쓰지 않는다</b>. 브리핑은 문단이라 가둘 스키마가 없다.
 *
 * <p>그래서 모델이 숫자를 문장 안에 직접 쓰고, 틀릴 수 있다. 이것은 사고가 아니라
 * 의도된 설계다 — 스펙 "거부한 것" 절 참조. 환각률을 재고 나서 구조를 정한다.
 */
@Slf4j
public class ClaudePurchaseOrderBriefingGenerator implements PurchaseOrderBriefingGenerator {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;

    public ClaudePurchaseOrderBriefingGenerator(AnthropicClient client, String model, long maxTokens) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.systemPrompt = readResource("prompts/purchase-order-briefing.txt");
    }

    @Override
    public Optional<Briefing> generate(BriefingSnapshot snapshot) {
        if (snapshot == null || snapshot.rows().isEmpty()) return Optional.empty();

        try {
            Message message = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .addUserMessage(renderInput(snapshot))
                    .build());

            String body = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining())
                    .trim();

            if (body.isBlank()) return Optional.empty();

            return Optional.of(new Briefing(body, message.model().asString(),
                    (int) message.usage().inputTokens(), (int) message.usage().outputTokens()));
        } catch (Exception e) {
            log.warn("발주 브리핑 생성 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 모델이 읽을 표. 채점기의 근거 집합이 이 값들이므로 <b>여기의 반올림 규칙이 곧 채점 기준</b>이다.
     * 일평균을 소수 1자리로 내면 모델이 "3.2"라고 쓰는 것이 정상 인용이 된다.
     */
    static String renderInput(BriefingSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("기준일: ").append(snapshot.generatedOn()).append("\n\n");
        for (BriefingSnapshot.Row r : snapshot.rows()) {
            sb.append("상품 #").append(r.productId()).append(" ").append(r.productName()).append("\n");
            sb.append("  최근 출고: ").append(r.shippedQty())
              .append("개 (표본 ").append(r.sampleDays()).append("일)\n");
            sb.append("  일평균: ").append(String.format("%.1f", r.dailyAverage())).append("개\n");
            sb.append("  가용: ").append(r.availableQty()).append("개\n");
            sb.append("  소진 예상: ")
              .append(r.daysToStockout() == null ? "없음" : String.format("%.1f일", r.daysToStockout()))
              .append("\n");
            sb.append("  직전 발주: ")
              .append(r.lastOrderId() == null ? "없음"
                      : "#" + r.lastOrderId() + " " + r.lastOrderedOn() + " " + r.lastOrderQty() + "개")
              .append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 리소스를 읽지 못했습니다: " + path, e);
        }
    }
}
```

- [ ] **Step 6: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingPromptRenderTest*'`
Expected: PASS (3건)

- [ ] **Step 7: 설정과 빈을 붙인다**

`src/main/resources/application.yml`의 `wms.ai` 블록(65~71줄) 끝에 두 줄을 더한다:

```yaml
  ai:
    api-key: ${ANTHROPIC_API_KEY:}
    model: ${WMS_AI_MODEL:claude-haiku-4-5}
    max-tokens: 1024
    timeout: 20s
    # 브리핑은 문단이라 출력 토큰이 분류(약 120)보다 훨씬 크고, 사용자가 화면에서 기다린다.
    # 분류 값을 그대로 쓰면 문단이 중간에서 잘린다.
    briefing-max-tokens: 2048
    briefing-timeout: 60s
```

`src/main/java/com/jhg/wms/config/AiConfig.java`에 빈을 더한다 (`purchaseOrderMemoClassifier` 빈 아래, `anthropicClient` private 메서드 위):

```java
    /**
     * 발주 브리핑 생성기. 키가 없으면 이것만 꺼진 채 기동하는 것은 분류 둘과 같다.
     * 다만 타임아웃·max-tokens는 따로 받는다 — 문단 생성은 분류와 크기가 다르다.
     */
    @Bean
    public PurchaseOrderBriefingGenerator purchaseOrderBriefingGenerator(
            @Value("${wms.ai.api-key:}") String apiKey,
            @Value("${wms.ai.model}") String model,
            @Value("${wms.ai.briefing-max-tokens}") long maxTokens,
            @Value("${wms.ai.briefing-timeout}") Duration timeout) {

        if (apiKey == null || apiKey.isBlank()) {
            log.info("ANTHROPIC_API_KEY 미설정 — 발주 브리핑을 끈 채로 기동합니다.");
            return snapshot -> Optional.empty();
        }

        log.info("발주 브리핑 활성: model={} maxTokens={} timeout={}", model, maxTokens, timeout);
        return new ClaudePurchaseOrderBriefingGenerator(anthropicClient(apiKey, timeout), model, maxTokens);
    }
```

import 두 줄을 파일 상단에 더한다:

```java
import com.jhg.wms.client.ClaudePurchaseOrderBriefingGenerator;
import com.jhg.wms.service.PurchaseOrderBriefingGenerator;
```

- [ ] **Step 8: 전체 테스트로 회귀를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, 524건. **기동 컨텍스트가 뜨는 테스트가 하나라도 실패하면 빈 배선이 틀린 것이다** — yml 키 이름을 먼저 확인할 것.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/PurchaseOrderBriefingGenerator.java \
        src/main/java/com/jhg/wms/client/ClaudePurchaseOrderBriefingGenerator.java \
        src/main/resources/prompts/purchase-order-briefing.txt \
        src/main/java/com/jhg/wms/config/AiConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/jhg/wms/client/BriefingPromptRenderTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 발주 브리핑 생성기를 붙인다

분류 둘과 달리 구조화 출력을 쓰지 않는다. 브리핑은 문단이라 가둘 스키마가 없다. 그래서 모델이
숫자를 문장 안에 직접 쓰고 틀릴 수 있는데, 이것은 사고가 아니라 의도된 설계다 — 구조화 출력으로
시작하면 사실성 채점이 자동으로 100%가 되어 평가가 배울 게 없다(스펙 "거부한 것" 절).

max-tokens와 timeout을 분류와 따로 받는다. 분류는 짧은 JSON이고 브리핑은 문단이라 1024로는
중간에서 잘린다. 그리고 사용자가 화면에서 기다리는 동기 호출이라 20초는 빠듯하다.

렌더의 반올림 규칙이 곧 채점 기준이다. 일평균을 소수 1자리로 내므로 모델이 "3.2"라고 쓰는 것이
정상 인용이 된다. 여기와 채점기가 어긋나면 정상 인용이 환각으로 잡히므로 테스트로 고정했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 브리핑 서비스

**Files:**
- Create: `src/main/java/com/jhg/wms/service/PurchaseOrderBriefingService.java`
- Test: `src/test/java/com/jhg/wms/service/PurchaseOrderBriefingServiceTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderBriefingGenerator`(Task 2), `PurchaseOrderBriefingRepository`·`BriefingSnapshot`(Task 1), `PurchaseOrderAdviceService.ProductAdvice`(기존)
- Produces: `PurchaseOrderBriefingService.generateAndSave(LocalDate, List<ProductAdvice>)` → `boolean`(성공 여부); `PurchaseOrderBriefingService.findLatest()` → `Optional<PurchaseOrderBriefing>`; `static final int TOP_N`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/service/PurchaseOrderBriefingServiceTest.java`:

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.domain.PurchaseOrderBriefing;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.repository.PurchaseOrderBriefingRepository;
import com.jhg.wms.service.PurchaseOrderAdviceService.LastOrder;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PurchaseOrderBriefingServiceTest {

    @Autowired private PurchaseOrderBriefingRepository repository;
    @Autowired private ObjectMapper objectMapper;

    private ProductAdvice advice(long id, String name) {
        return new ProductAdvice(id, name, 60, 30L, 2.0, 20, 5, 15, 7.5,
                new LastOrder(900L, PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 9, 1), 50,
                        LocalDate.of(2026, 9, 3), 2L));
    }

    private PurchaseOrderBriefingService service(PurchaseOrderBriefingGenerator generator) {
        return new PurchaseOrderBriefingService(generator, repository, objectMapper);
    }

    @Test
    void 생성에_성공하면_본문과_스냅샷을_같이_저장한다() {
        var service = service(s -> Optional.of(
                new PurchaseOrderBriefingGenerator.Briefing("볼펜을 먼저 넣으세요.", "haiku", 400, 90)));

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isTrue();
        var stored = repository.findFirstByOrderByCreatedAtDesc().orElseThrow();
        assertThat(stored.getBody()).isEqualTo("볼펜을 먼저 넣으세요.");
        assertThat(stored.getInputSnapshot()).contains("\"productId\":1");
    }

    // 생성이 실패해도 예외가 밖으로 나가지 않는다. 브리핑이 없어도 발주 업무는 돈다.
    @Test
    void 생성에_실패하면_저장하지_않고_false를_낸다() {
        var service = service(s -> Optional.empty());

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(advice(1, "볼펜")));

        assertThat(saved).isFalse();
        assertThat(repository.findFirstByOrderByCreatedAtDesc()).isEmpty();
    }

    // 근거가 아예 없으면 모델을 부르지 않는다 — 부를 이유도 없고 토큰만 쓴다.
    @Test
    void 근거가_비면_모델을_부르지_않는다() {
        var 불림 = new boolean[]{false};
        var service = service(s -> { 불림[0] = true; return Optional.empty(); });

        boolean saved = service.generateAndSave(LocalDate.of(2026, 9, 10), List.of());

        assertThat(saved).isFalse();
        assertThat(불림[0]).isFalse();
    }

    @Test
    void 상위_다섯_개만_스냅샷에_담는다() {
        var captured = new BriefingSnapshot[1];
        var service = service(s -> {
            captured[0] = s;
            return Optional.of(new PurchaseOrderBriefingGenerator.Briefing("x", "haiku", 1, 1));
        });

        service.generateAndSave(LocalDate.of(2026, 9, 10), List.of(
                advice(1, "A"), advice(2, "B"), advice(3, "C"),
                advice(4, "D"), advice(5, "E"), advice(6, "F")));

        assertThat(captured[0].rows()).hasSize(PurchaseOrderBriefingService.TOP_N);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*PurchaseOrderBriefingServiceTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class PurchaseOrderBriefingService`

- [ ] **Step 3: 서비스를 만든다**

`src/main/java/com/jhg/wms/service/PurchaseOrderBriefingService.java`:

```java
package com.jhg.wms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.domain.PurchaseOrderBriefing;
import com.jhg.wms.repository.PurchaseOrderBriefingRepository;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 브리핑 생성과 저장.
 *
 * <p>분류 둘과 달리 <b>동기</b>다. 반품·메모 분류는 커밋 후 별도 스레드에서 돌고 사용자는
 * 기다리지 않지만, 브리핑은 사용자가 버튼을 누르고 결과를 기다리는 읽기 시점이라
 * 비동기 트리거 패턴을 쓸 수 없다.
 *
 * <p>그래도 커넥션 문제는 같다. {@code generateAndSave}를 통째로 {@code @Transactional}로
 * 두면 HTTP 호출이 끝날 때까지(최대 60초) DB 커넥션을 붙잡는다. 그래서 <b>생성은 트랜잭션
 * 밖에서 하고 저장만 짧은 트랜잭션으로 감싼다</b> — {@code NOT_SUPPORTED}로 시작해
 * 저장 시점에만 {@code REQUIRES_NEW}로 들어가는 모양이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderBriefingService {

    // ponytail: 상위 5개는 근거 없는 값이다. 브리핑이 흐리면 줄이고, 놓치는 상품이 보이면 늘린다.
    public static final int TOP_N = 5;

    private final PurchaseOrderBriefingGenerator generator;
    private final PurchaseOrderBriefingRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @return 저장에 성공했으면 true. 실패는 예외가 아니라 false다 —
     *         브리핑이 없어도 발주 업무는 돌아야 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean generateAndSave(LocalDate today, List<ProductAdvice> advice) {
        if (advice == null || advice.isEmpty()) {
            log.debug("근거가 비어 브리핑을 만들지 않습니다.");
            return false;
        }

        BriefingSnapshot snapshot = BriefingSnapshot.of(today, advice, TOP_N);
        try {
            Optional<PurchaseOrderBriefingGenerator.Briefing> result = generator.generate(snapshot);
            if (result.isEmpty()) {
                log.warn("발주 브리핑 없음(무시)");
                return false;
            }
            var b = result.get();
            save(PurchaseOrderBriefing.create(b.body(), objectMapper.writeValueAsString(snapshot),
                    b.model(), b.inputTokens(), b.outputTokens()));
            log.info("발주 브리핑 생성: model={} in={} out={}", b.model(), b.inputTokens(), b.outputTokens());
            return true;
        } catch (Exception e) {
            log.warn("발주 브리핑 실패(무시)", e);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void save(PurchaseOrderBriefing briefing) {
        repository.save(briefing);
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseOrderBriefing> findLatest() {
        return repository.findFirstByOrderByCreatedAtDesc();
    }
}
```

**주의:** `save`가 `protected`이고 같은 빈 안에서 불리면 Spring AOP 프록시를 안 타서 `REQUIRES_NEW`가 적용되지 않는다. Step 4에서 테스트가 통과해도 이 문제는 안 드러난다 — **Step 5에서 반드시 고친다.**

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*PurchaseOrderBriefingServiceTest*'`
Expected: PASS (4건)

- [ ] **Step 5: 자기호출 문제를 없앤다**

`save` 메서드를 지우고 `generateAndSave` 안의 `save(...)` 호출을 `repository.save(...)`로 바꾼다.

**왜:** 같은 빈 안에서 부르는 `protected` 메서드는 프록시를 안 타므로 `REQUIRES_NEW`가 무시된다. 있으나 마나 한 애노테이션은 다음 사람에게 "여기는 새 트랜잭션"이라고 거짓말을 한다. `NOT_SUPPORTED` 안에서 `repository.save()`를 부르면 Spring Data가 자기 트랜잭션(`SimpleJpaRepository`의 `@Transactional`)을 열고 바로 닫으므로 **의도한 동작이 그대로 나온다.** 클래스 주석의 `REQUIRES_NEW` 언급도 지운다.

바뀐 주석 문단:

```java
 * <p>그래도 커넥션 문제는 같다. {@code generateAndSave}를 통째로 {@code @Transactional}로
 * 두면 HTTP 호출이 끝날 때까지(최대 60초) DB 커넥션을 붙잡는다. 그래서 {@code NOT_SUPPORTED}로
 * 트랜잭션 밖에서 돌고, 저장은 {@code repository.save()}가 여는 자기 트랜잭션에 맡긴다 —
 * 커넥션을 잡는 구간이 저장 한 번으로 좁아진다.
```

- [ ] **Step 6: 다시 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*PurchaseOrderBriefingServiceTest*'`
Expected: PASS (4건)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/PurchaseOrderBriefingService.java \
        src/test/java/com/jhg/wms/service/PurchaseOrderBriefingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 브리핑 생성·저장 서비스를 만든다

분류 둘과 달리 동기다. 사용자가 버튼을 누르고 결과를 기다리는 읽기 시점이라 커밋 후 비동기
트리거 패턴을 쓸 수 없다. 그래도 커넥션 문제는 같아서 NOT_SUPPORTED로 트랜잭션 밖에서 돌고
저장은 repository.save()가 여는 자기 트랜잭션에 맡긴다 — 통째로 @Transactional이면 HTTP
호출이 끝날 때까지 최대 60초 커넥션을 붙잡는다.

REQUIRES_NEW를 붙인 protected save()를 먼저 썼다가 지웠다. 같은 빈 안에서 부르면 프록시를
안 타서 적용되지 않는데, 있으나 마나 한 애노테이션은 다음 사람에게 "여기는 새 트랜잭션"이라고
거짓말을 한다.

실패는 예외가 아니라 false다. 브리핑이 없어도 발주 업무는 돌아야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 화면 — 버튼과 브리핑 표시

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java:242-257`(GET 핸들러에 모델 속성 추가), 그 아래에 POST 핸들러 추가
- Modify: `src/main/resources/templates/admin/purchaseorders.html`
- Test: `src/test/java/com/jhg/wms/web/PurchaseOrderBriefingControllerTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderBriefingService.generateAndSave(LocalDate, List<ProductAdvice>)`, `.findLatest()`(Task 3)
- Produces: `POST /admin/purchase-orders/briefing` → 302 `/admin/purchase-orders`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/web/PurchaseOrderBriefingControllerTest.java`:

```java
package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderBriefingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class PurchaseOrderBriefingControllerTest {

    @Autowired private WebApplicationContext context;
    @MockBean private PurchaseOrderBriefingService briefingService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 브리핑_생성은_발주_목록으로_리다이렉트한다() throws Exception {
        given(briefingService.generateAndSave(any(), any())).willReturn(true);

        mvc().perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    // 실패해도 화면은 그대로 돌아간다. 브리핑이 없어도 발주 업무는 돈다.
    @Test
    @WithMockUser(roles = "ADMIN")
    void 생성_실패는_에러_메시지만_남기고_같은_화면으로_돌아간다() throws Exception {
        given(briefingService.generateAndSave(any(), any())).willReturn(false);

        mvc().perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 인증_없이는_거부한다() throws Exception {
        mvc().perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
```

`csrf()` import는 `org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf`다. 파일 상단에 `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;`를 더한다.

**먼저 기존 MockMvc 테스트 하나를 열어 이 저장소의 방식과 맞추라:** `grep -rln 'MockMvc' src/test/java/com/jhg/wms/web/ | head -3`. 다른 파일이 `@WebMvcTest`나 다른 셋업을 쓰면 **그쪽을 따른다.** 위 코드는 그 확인 뒤에 맞춰 고칠 것.

- [ ] **Step 2: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*PurchaseOrderBriefingControllerTest*'`
Expected: FAIL — 404 또는 405 (핸들러 없음)

- [ ] **Step 3: 컨트롤러에 핸들러를 더한다**

`WmsAdminController`의 필드에 서비스를 더한다 (33줄 `purchaseOrderAdviceService` 옆):

```java
    private final PurchaseOrderBriefingService purchaseOrderBriefingService;
```

GET 핸들러(242~257줄)의 `return "admin/purchaseorders";` 바로 위에 두 줄을 더한다:

```java
        model.addAttribute("briefing", purchaseOrderBriefingService.findLatest().orElse(null));
        model.addAttribute("briefingEnabled", briefingEnabled);
```

`briefingEnabled`는 클래스 필드로 받는다 — 키가 없으면 버튼을 그리지 않기 위해서다:

```java
    @Value("${wms.ai.api-key:}")
    private String aiApiKey;

    private boolean isBriefingEnabled() {
        return aiApiKey != null && !aiApiKey.isBlank();
    }
```

위 `model.addAttribute("briefingEnabled", briefingEnabled);`를 `model.addAttribute("briefingEnabled", isBriefingEnabled());`로 쓴다.

POST 핸들러를 `createPo` 아래에 더한다:

```java
    /**
     * 브리핑 생성. 근거는 GET 핸들러와 같은 방식으로 다시 만든다 —
     * 리다이렉트 뒤의 GET이 최신 재고로 패널을 다시 그리므로 여기서 캐시할 것이 없다.
     */
    @PostMapping("/admin/purchase-orders/briefing")
    public String generateBriefing(RedirectAttributes ra) {
        List<InventoryRowResponse> rows = inventoryService.findAllRows();
        var advice = purchaseOrderAdviceService.advise(
                LocalDate.now(), rows, purchaseOrderService.findAllWithItems());

        if (purchaseOrderBriefingService.generateAndSave(LocalDate.now(), advice)) {
            ra.addFlashAttribute("successMessage", "브리핑을 만들었습니다.");
        } else {
            ra.addFlashAttribute("errorMessage", "브리핑을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return "redirect:/admin/purchase-orders";
    }
```

- [ ] **Step 4: 템플릿에 버튼과 표시를 더한다**

`src/main/resources/templates/admin/purchaseorders.html`에서 근거 패널 표가 끝나는 위치를 찾는다(`grep -n 'advice' src/main/resources/templates/admin/purchaseorders.html`). 그 **아래**에 넣는다:

```html
<div th:if="${briefingEnabled}" style="margin:16px 0;padding:12px;border:1px solid #ddd;border-radius:6px;">
  <form th:action="@{/admin/purchase-orders/briefing}" method="post" style="margin:0 0 8px 0;">
    <button type="submit">브리핑 받기</button>
    <span style="color:#666;font-size:12px;margin-left:8px;">
      위 표 상위 5개를 읽어 요약합니다. 참고용이며 발주 내용에 영향을 주지 않습니다.
    </span>
  </form>
  <div th:if="${briefing != null}">
    <p th:text="${briefing.body}" style="margin:8px 0;line-height:1.6;"></p>
    <p style="color:#888;font-size:11px;margin:0;">
      <span th:text="${briefing.model}"></span> ·
      <span th:text="${briefing.createdAt}"></span>
    </p>
  </div>
</div>
```

**주의:** 이 저장소의 다른 템플릿이 인라인 스타일을 안 쓰고 CSS 클래스를 쓰면 그쪽을 따른다. `head -40 src/main/resources/templates/admin/purchaseorders.html`로 먼저 확인할 것.

- [ ] **Step 5: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*PurchaseOrderBriefingControllerTest*'`
Expected: PASS (3건)

- [ ] **Step 6: 전체 테스트로 회귀를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, 531건

- [ ] **Step 7: 실기동 검증**

V10.0에서 이 단계를 빼먹어 원장에 "미검증" 절이 남았다. 반복하지 않는다.

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY \
  ./gradlew bootRun --args='--server.port=8082'
```

확인할 것 — **본 것을 원장에 적는다:**
1. `/admin/purchase-orders`에 버튼이 보인다
2. 눌렀을 때 문단이 나오고 모델명·시각이 붙는다
3. **생성된 문단의 숫자가 위 표의 값과 실제로 맞는가** — 이 눈으로 본 결과가 Task 9 평가의 사전 관측이 된다
4. `ANTHROPIC_API_KEY=` 를 빈 값으로 기동하면 버튼이 안 보이고 화면은 정상이다

- [ ] **Step 8: 커밋하고 1부를 원장에 적는다**

`.superpowers/sdd/progress.md`에 append (append만 한다 — 기존 내용을 고치지 않는다):

```markdown
## V11.0 1부 — 발주 브리핑 기능 (2026-09-10, `feat/wms-purchase-order-briefing`)

버튼 하나로 근거 패널 상위 5개를 읽은 문단을 만들어 저장한다. 설계는
`docs/superpowers/specs/v11/2026-09-10-purchase-order-briefing-design.md`.

**분류 둘과 다른 점이 셋이다.** ① 동기다(읽기 시점이라 커밋 후 비동기 트리거를 못 쓴다).
② 구조화 출력이 없다(문단이라 가둘 스키마가 없고, 그래서 숫자를 틀릴 수 있다 — 의도된 설계다).
③ `purchaseOrderId`가 없다(발주 하나가 아니라 화면 전체를 읽는다).

**`input_snapshot`을 본문과 같이 저장한다.** 재고가 계속 변해서, 스냅샷이 없으면 나중에
"이 문장의 숫자가 맞았나"를 판정할 수 없다. 2부의 채점기가 먹는 것이 이 값이다.

**거부한 것:** `REQUIRES_NEW`를 붙인 `protected save()`. 같은 빈 안에서 부르면 프록시를 안
타서 적용되지 않는데, 있으나 마나 한 애노테이션은 다음 사람에게 거짓말을 한다.
`NOT_SUPPORTED` + `repository.save()`의 자기 트랜잭션으로 같은 결과를 얻는다.

**실기동 검증(8082):** [여기에 Step 7에서 본 것을 적는다 — 특히 3번, 숫자가 맞았는지]

기준선 Java 531건 그린.
```

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(wms): 발주 화면에 브리핑 버튼을 붙인다

키가 없으면 버튼을 아예 그리지 않는다 — 눌러도 안 되는 버튼을 보여주는 것보다 낫고,
"키 없으면 그 기능만 비활성"이라는 기존 규칙과도 같다.

실패해도 flash 에러만 남기고 같은 화면으로 돌아간다. 브리핑이 없어도 발주 업무는 돈다.

리다이렉트 뒤의 GET이 최신 재고로 패널을 다시 그리므로 POST 핸들러에서 근거를 캐시하지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

**여기까지가 1부다. PR을 열어 병합하고 2부는 새 브랜치에서 시작한다** — V10.0처럼 두 단계를 한 브랜치에 실으면 리뷰 기록이 그 구간만 빈다.

---

# 2부 — 평가

## Task 5: GroundingScorer — 숫자 대조 채점기

**Files:**
- Create: `src/test/java/com/jhg/wms/eval/GroundingScorer.java`
- Test: `src/test/java/com/jhg/wms/eval/GroundingScorerTest.java`

**Interfaces:**
- Consumes: `BriefingSnapshot`(Task 1)
- Produces: `GroundingScorer.score(String body, BriefingSnapshot snapshot)` → `GroundingScorer.Result`; `record Result(int total, List<String> ungrounded)`; `Result.grounded()` → `int`; `Result.clean()` → `boolean`

**이 태스크가 2부에서 가장 중요하다.** 모델 없이 도는 확정적 로직이라 여기가 틀리면 모든 점수가 틀린다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/GroundingScorerTest.java`:

```java
package com.jhg.wms.eval;

import com.jhg.wms.domain.BriefingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채점기가 틀리면 모든 점수가 틀린다. 모델 없이 도는 확정적 로직이라 여기서 다 가둔다.
 */
class GroundingScorerTest {

    // 일평균 3.2333, 소진 예상 4.6875, 가용 15, 출고 97, 표본 30일, 직전 발주 #900 · 50개
    private final BriefingSnapshot snapshot = new BriefingSnapshot(
            LocalDate.of(2026, 9, 10),
            List.of(new BriefingSnapshot.Row(7L, "테이프", 97, 30L, 3.2333, 15, 4.6875,
                    900L, LocalDate.of(2026, 9, 1), 50)));

    @Test
    void 표에_있는_값을_그대로_쓰면_통과한다() {
        var r = GroundingScorer.score("가용 15개이고 최근 출고는 97개입니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.clean()).isTrue();
    }

    // 렌더가 소수 1자리로 주므로 모델이 "3.2"라고 쓰는 것이 정상 인용이다.
    @Test
    void 소수_한_자리로_반올림한_인용은_통과한다() {
        var r = GroundingScorer.score("일평균 3.2개씩 나갑니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 정수로_반올림한_인용도_통과한다() {
        var r = GroundingScorer.score("하루 3개쯤 나갑니다.", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 표에_없는_숫자는_잡는다() {
        var r = GroundingScorer.score("일평균 7.4개입니다.", snapshot);

        assertThat(r.ungrounded()).containsExactly("7.4");
        assertThat(r.clean()).isFalse();
    }

    // 계산 금지가 프롬프트에 명시돼 있으므로 파생값은 오탐이 아니라 진짜 위반이다.
    @Test
    void 두_값을_더한_파생값은_잡는다() {
        var r = GroundingScorer.score("합쳐서 112개를 봐야 합니다.", snapshot);   // 97 + 15

        assertThat(r.ungrounded()).containsExactly("112");
    }

    // 발주 번호는 반올림 대상이 아니다. #900을 #3으로 반올림해 통과시키면 안 된다.
    @Test
    void 발주_번호와_상품_번호는_정확히_일치해야_한다() {
        var ok = GroundingScorer.score("직전 발주 #900은 50개였습니다.", snapshot);
        var no = GroundingScorer.score("직전 발주 #901을 보세요.", snapshot);

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).containsExactly("901");
    }

    // 창 길이 30일과 상위 5개는 표에 없지만 환각이 아니다. 화이트리스트로 뺀다.
    // 다만 넓히면 진짜 환각도 통과하므로 이 둘만 둔다.
    @Test
    void 창_길이와_목록_개수는_화이트리스트다() {
        var r = GroundingScorer.score("최근 30일 기준 상위 5개 중에서는", snapshot);

        assertThat(r.ungrounded()).isEmpty();
    }

    @Test
    void 날짜는_표의_날짜와_맞아야_한다() {
        var ok = GroundingScorer.score("9월 1일에 발주했습니다.", snapshot);
        var no = GroundingScorer.score("9월 5일에 발주했습니다.", snapshot);

        assertThat(ok.ungrounded()).isEmpty();
        assertThat(no.ungrounded()).contains("5");
    }

    @Test
    void 숫자가_하나도_없으면_total이_0이고_clean이다() {
        var r = GroundingScorer.score("지금 급한 상품은 없습니다.", snapshot);

        assertThat(r.total()).isZero();
        assertThat(r.clean()).isTrue();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*GroundingScorerTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class GroundingScorer`

- [ ] **Step 3: 채점기를 만든다**

`src/test/java/com/jhg/wms/eval/GroundingScorer.java`:

```java
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

        List<String> ungrounded = new ArrayList<>();
        int total = 0;
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            String token = m.group();
            total++;
            if (!grounded(token, exact, measures)) ungrounded.add(token);
        }
        return new Result(total, ungrounded);
    }

    private static boolean grounded(String token, Set<String> exact, List<Double> measures) {
        if (WHITELIST.contains(token)) return true;
        if (exact.contains(token)) return true;
        if (exact.contains(stripTrailingZeros(token))) return true;

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
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*GroundingScorerTest*'`
Expected: PASS (9건)

**하나라도 실패하면 채점기가 아니라 테스트가 맞다고 보고 채점기를 고친다.** 특히 `발주_번호와_상품_번호는_정확히_일치해야_한다`가 실패하면 근거 집합을 둘로 나눈 것이 안 먹은 것이다.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/GroundingScorer.java \
        src/test/java/com/jhg/wms/eval/GroundingScorerTest.java
git commit -m "$(cat <<'EOF'
test(wms): 브리핑의 숫자를 스냅샷과 대조하는 채점기를 만든다

모델을 부르지 않는다. 확정적이고 공짜다. 이 채점기가 틀리면 모든 점수가 틀리므로 단위 테스트로
경계를 다 가뒀다.

근거 집합을 둘로 나눴다. 식별자·정수(상품 번호·발주 번호·수량·날짜)는 정확히 일치해야 하고
실측치(일평균·소진 예상)만 반올림을 인정한다. 하나로 합치면 일평균 3.2333이 정수 3으로
반올림되면서 "발주 #3" 같은 환각까지 통과한다.

화이트리스트는 30(창 길이)과 5(상위 개수) 둘뿐이다. 넓히면 진짜 환각도 같이 통과한다 —
이 줄다리기가 자동 채점기 설계의 핵심이라 주석에 남겼다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: judge — 체크리스트 채점기

**Files:**
- Create: `src/test/resources/prompts/briefing-judge.txt`
- Create: `src/test/resources/prompts/briefing-judge-schema.json`
- Create: `src/test/java/com/jhg/wms/eval/BriefingJudge.java`
- Test: `src/test/java/com/jhg/wms/eval/BriefingJudgeParseTest.java`

**Interfaces:**
- Consumes: `BriefingSnapshot`(Task 1)
- Produces: `BriefingJudge.ITEMS` → `List<String>`(순서 고정: `"REASONED"`, `"NO_INVENTED_FACTS"`, `"ACTIONABLE"`); `new BriefingJudge(AnthropicClient, ObjectMapper, String model, long maxTokens)`; `judge(String body, BriefingSnapshot)` → `Optional<Verdict>`; `record Verdict(Map<String,Boolean> items, String model, int inputTokens, int outputTokens)`; `BriefingJudge.parse(ObjectMapper, String json)` → `Optional<Map<String,Boolean>>` (static, 테스트가 부른다)

**스펙 수정:** 스펙은 judge 프롬프트를 `src/main/resources/prompts/`에 둔다고 적었으나 **`src/test/resources/prompts/`가 맞다.** judge는 운영 코드가 아니라 평가 전용이고, main에 두면 운영 jar에 채점 프롬프트가 실린다. Task 9에서 스펙에 이 정정을 적는다.

- [ ] **Step 1: judge 프롬프트를 쓴다**

`src/test/resources/prompts/briefing-judge.txt`:

```
너는 창고 발주 브리핑을 채점한다. 아래에 담당자가 본 실측치 표와, 그 표를 읽고 쓰인 브리핑이 있다.
세 항목에 예/아니오로만 답한다.

REASONED
  브리핑이 고른 상품마다 왜 급한지 표의 값으로 근거를 댔는가?
  "볼펜을 발주하세요"처럼 근거 없이 지시만 있으면 아니오다.
  급한 상품이 없다고 판단한 브리핑은 그 판단의 근거가 있으면 예다.

NO_INVENTED_FACTS
  표에 없는 사실을 지어내지 않았는가?
  거래처 사정·시즌·행사·리드타임·과거 추세는 이 표에 없다. 그런 말이 있으면 아니오다.
  숫자의 정확성은 보지 마라 — 그건 다른 채점기가 이미 본다.

ACTIONABLE
  담당자가 이걸 읽고 바로 다음 행동을 정할 수 있는가?
  무엇을 먼저 볼지가 분명하면 예다. 표를 말로 옮기기만 했으면 아니오다.

판단이 애매하면 아니오를 골라라. 좋게 봐주면 채점이 쓸모없어진다.
```

`src/test/resources/prompts/briefing-judge-schema.json`:

```json
{
  "type": "object",
  "properties": {
    "REASONED": { "type": "boolean" },
    "NO_INVENTED_FACTS": { "type": "boolean" },
    "ACTIONABLE": { "type": "boolean" }
  },
  "required": ["REASONED", "NO_INVENTED_FACTS", "ACTIONABLE"],
  "additionalProperties": false
}
```

- [ ] **Step 2: 파싱 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/BriefingJudgeParseTest.java`:

```java
package com.jhg.wms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge 응답 파싱만 검증한다. API 호출 없이 돈다.
 */
class BriefingJudgeParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 세_항목을_모두_읽는다() {
        var parsed = BriefingJudge.parse(mapper,
                "{\"REASONED\":true,\"NO_INVENTED_FACTS\":false,\"ACTIONABLE\":true}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).containsEntry("REASONED", true)
                .containsEntry("NO_INVENTED_FACTS", false)
                .containsEntry("ACTIONABLE", true);
    }

    // 항목이 빠진 응답을 반만 읽으면 없는 항목이 조용히 통과한다. 통째로 버린다.
    @Test
    void 항목이_빠지면_empty다() {
        var parsed = BriefingJudge.parse(mapper, "{\"REASONED\":true}");

        assertThat(parsed).isEmpty();
    }

    @Test
    void 깨진_JSON은_empty다() {
        assertThat(BriefingJudge.parse(mapper, "이건 JSON이 아니다")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingJudgeParseTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class BriefingJudge`

- [ ] **Step 4: judge를 만든다**

`src/test/java/com/jhg/wms/eval/BriefingJudge.java`:

```java
package com.jhg.wms.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudePurchaseOrderBriefingGenerator;
import com.jhg.wms.domain.BriefingSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 브리핑의 유용성을 체크리스트로 채점한다.
 *
 * <p><b>기계가 잴 수 있는 것은 여기서 묻지 않는다.</b> 숫자 정확성은 {@link GroundingScorer}가
 * 확정적으로 답하고, "가장 급한 상품을 언급했는가"도 스냅샷과 본문 대조로 답할 수 있다.
 * 확정적으로 답할 수 있는 것을 모델에게 물으면 비용을 더 내고 답을 덜 믿게 된다.
 *
 * <p>점수(1~5)가 아니라 예/아니오다. 점수는 회차마다 흔들려 "3.4 → 3.7"이 개선인지 잡음인지
 * 가릴 수 없다. 예/아니오면 {@link EvalAggregator}의 3회 다수결이 그대로 적용된다.
 */
public class BriefingJudge {

    /** 순서 고정 — 리포트의 열 순서가 회차마다 바뀌면 비교가 안 된다. */
    public static final List<String> ITEMS = List.of("REASONED", "NO_INVENTED_FACTS", "ACTIONABLE");

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;
    private final JsonOutputFormat.Schema schema;

    public BriefingJudge(AnthropicClient client, ObjectMapper objectMapper, String model, long maxTokens) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.systemPrompt = readResource("prompts/briefing-judge.txt");
        this.schema = toSchema(objectMapper, readResource("prompts/briefing-judge-schema.json"));
    }

    public record Verdict(Map<String, Boolean> items, String model, int inputTokens, int outputTokens) {}

    public Optional<Verdict> judge(String body, BriefingSnapshot snapshot) {
        try {
            String user = "[표]\n" + ClaudePurchaseOrderBriefingGenerator.renderInput(snapshot)
                    + "\n\n[브리핑]\n" + body;

            Message message = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .outputConfig(OutputConfig.builder()
                            .format(JsonOutputFormat.builder().schema(schema).build())
                            .build())
                    .addUserMessage(user)
                    .build());

            String json = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());

            return parse(objectMapper, json).map(items -> new Verdict(items,
                    message.model().asString(),
                    (int) message.usage().inputTokens(),
                    (int) message.usage().outputTokens()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 항목이 하나라도 빠지면 통째로 버린다 — 반만 읽으면 없는 항목이 조용히 통과한다. */
    static Optional<Map<String, Boolean>> parse(ObjectMapper mapper, String json) {
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<>() {});
            Map<String, Boolean> items = new LinkedHashMap<>();
            for (String item : ITEMS) {
                Object v = raw.get(item);
                if (!(v instanceof Boolean b)) return Optional.empty();
                items.put(item, b);
            }
            return Optional.of(items);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String readResource(String path) {
        try (var in = BriefingJudge.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("리소스 없음: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonOutputFormat.Schema toSchema(ObjectMapper mapper, String json) {
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<>() {});
            JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
            map.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
            return b.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

**주의:** `toSchema`는 `ClaudePurchaseOrderMemoClassifier`의 같은 이름 메서드를 그대로 옮긴 것이다. 그 파일을 열어 실제 구현을 확인하고 **거기 있는 것을 그대로 쓴다** — SDK 버전에 따라 빌더 모양이 다르다.

- [ ] **Step 5: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingJudgeParseTest*'`
Expected: PASS (3건)

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/BriefingJudge.java \
        src/test/java/com/jhg/wms/eval/BriefingJudgeParseTest.java \
        src/test/resources/prompts/briefing-judge.txt \
        src/test/resources/prompts/briefing-judge-schema.json
git commit -m "$(cat <<'EOF'
test(wms): 브리핑 유용성을 예/아니오로 채점하는 judge를 만든다

기계가 잴 수 있는 것은 judge에게 묻지 않는다. 숫자 정확성은 GroundingScorer가 확정적으로
답하고 "가장 급한 상품을 언급했는가"도 대조로 답할 수 있다. 확정적으로 답할 수 있는 것을
모델에게 물으면 비용을 더 내고 답을 덜 믿게 된다.

점수(1~5)가 아니라 예/아니오다. 점수는 회차마다 흔들려 3.4 → 3.7이 개선인지 잡음인지 가릴
수 없다. 예/아니오면 EvalAggregator의 3회 다수결이 그대로 적용된다.

항목이 하나라도 빠진 응답은 통째로 버린다. 반만 읽으면 없는 항목이 조용히 통과한다.

프롬프트를 test 리소스에 둔다. 스펙은 main이라고 적었으나 judge는 평가 전용이라 운영 jar에
채점 프롬프트가 실릴 이유가 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 평가셋

**Files:**
- Create: `src/test/resources/eval/briefing-cases.json`
- Create: `src/test/java/com/jhg/wms/eval/BriefingEvalCase.java`
- Test: `src/test/java/com/jhg/wms/eval/BriefingEvalCaseLoadTest.java`

**Interfaces:**
- Consumes: `BriefingSnapshot`(Task 1), `BriefingJudge.ITEMS`(Task 6)
- Produces: `BriefingEvalCase.loadAll(String resourcePath)` → `List<BriefingEvalCase>`; `record BriefingEvalCase(String id, BriefingSnapshot snapshot, String body, String failingItem, String note)`; `.isNegative()` → `boolean`

**케이스 두 종류:**

| 종류 | `body` | `failingItem` | 생성 | 채점 |
|---|---|---|---|---|
| 정상 | `null` | `null` | 한다 | GroundingScorer + judge(세 항목 전부 `YES` 기대) |
| 네거티브 | **본문 있음** | 항목명 또는 `"GROUNDING"` | **안 한다** | 지정된 것만 |

**네거티브 본문은 사람이 쓴다.** 모델에게 "망가진 브리핑을 써라"라고 시키면 무엇이 망가졌는지를 모델이 정하게 되고, judge를 같은 모델의 판단으로 검증하는 순환이 된다.

- [ ] **Step 1: 평가셋을 쓴다**

`src/test/resources/eval/briefing-cases.json` — 정상 8건은 **재고 모양이 서로 달라야 한다.** 같은 모양을 여덟 번 재면 표본이 하나다. 아래 골격에 값을 채운다. `snapshot.rows[]`의 필드는 `BriefingSnapshot.Row`와 정확히 같은 이름이어야 한다(`productId, productName, shippedQty, sampleDays, dailyAverage, availableQty, daysToStockout, lastOrderId, lastOrderedOn, lastOrderQty`).

```json
{
  "cases": [
    {
      "id": "pos-01",
      "note": "소진 임박이 뚜렷한 상품 하나 + 여유 있는 넷",
      "snapshot": {
        "generatedOn": "2026-09-10",
        "rows": [
          {"productId": 3, "productName": "A4용지", "shippedQty": 240, "sampleDays": 30,
           "dailyAverage": 8.0, "availableQty": 12, "daysToStockout": 1.5,
           "lastOrderId": 812, "lastOrderedOn": "2026-08-20", "lastOrderQty": 200},
          {"productId": 7, "productName": "테이프", "shippedQty": 97, "sampleDays": 30,
           "dailyAverage": 3.2333, "availableQty": 60, "daysToStockout": 18.5,
           "lastOrderId": 800, "lastOrderedOn": "2026-08-15", "lastOrderQty": 100}
        ]
      }
    }
  ]
}
```

**정상 8건이 덮어야 할 모양** (하나씩 `pos-01`~`pos-08`):

1. `pos-01` 소진 임박 하나 + 여유 넷
2. `pos-02` 소진 임박이 셋 이상 — 우선순위를 정해야 하는 모양
3. `pos-03` **소진 임박이 하나도 없다** — "지금 급한 것 없음"을 쓸 수 있는가
4. `pos-04` `daysToStockout`이 `null`인 상품이 섞임(일평균 0) — "없음"을 "0일"로 안 읽는가
5. `pos-05` 직전 발주가 `null`인 상품 — 없는 값을 지어내지 않는가
6. `pos-06` **직전 발주가 최근인데 아직 재고가 적음** — 중복 발주를 권하지 않는가
7. `pos-07` `sampleDays`가 짧음(3~5일) — 얇은 표본을 단정하지 않는가
8. `pos-08` 상품이 하나뿐 — 상위 5개가 안 차는 경우

**네거티브 4건:**

| id | `failingItem` | 어떻게 망가뜨리나 |
|---|---|---|
| `neg-reasoned` | `REASONED` | 상품명만 나열하고 근거를 다 뺀다 |
| `neg-invented` | `NO_INVENTED_FACTS` | "거래처 휴무가 다음 주라" 같은 표에 없는 사실을 넣는다 |
| `neg-actionable` | `ACTIONABLE` | 표를 문장으로 옮기기만 하고 무엇을 먼저 볼지 안 쓴다 |
| `neg-grounding` | `GROUNDING` | 정상 본문의 숫자 하나를 표에 없는 값으로 바꾼다 |

`neg-grounding`은 judge에게 보내지 않는다 — `GroundingScorer`가 잡아야 하고, 그건 API 호출이 필요 없다.

- [ ] **Step 2: 로드 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/BriefingEvalCaseLoadTest.java`:

```java
package com.jhg.wms.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가셋의 형식 오류를 컴파일이 못 잡으므로 여기서 잡는다.
 * {@code EvalCaseLoadTest}·{@code MemoEvalCaseLoadTest}와 같은 이유다.
 */
class BriefingEvalCaseLoadTest {

    private final List<BriefingEvalCase> cases = BriefingEvalCase.loadAll("eval/briefing-cases.json");

    @Test
    void 정상_여덟_건과_네거티브_네_건이다() {
        assertThat(cases.stream().filter(c -> !c.isNegative())).hasSize(8);
        assertThat(cases.stream().filter(BriefingEvalCase::isNegative)).hasSize(4);
    }

    @Test
    void id가_겹치지_않는다() {
        assertThat(cases).extracting(BriefingEvalCase::id).doesNotHaveDuplicates();
    }

    // failingItem이 오타면 그 네거티브는 영원히 통과한다 — 채점할 항목을 못 찾으니까.
    @Test
    void 네거티브의_failingItem은_judge_항목이거나_GROUNDING이다() {
        assertThat(cases.stream().filter(BriefingEvalCase::isNegative))
                .allSatisfy(c -> assertThat(c.failingItem())
                        .isIn(java.util.stream.Stream.concat(
                                BriefingJudge.ITEMS.stream(), java.util.stream.Stream.of("GROUNDING"))
                                .toList()));
    }

    @Test
    void 정상_케이스는_본문이_없고_네거티브는_있다() {
        assertThat(cases).allSatisfy(c -> {
            if (c.isNegative()) assertThat(c.body()).isNotBlank();
            else assertThat(c.body()).isNull();
        });
    }

    // 같은 모양을 여덟 번 재면 표본이 하나다. 최소한 소진 임박 상품 수는 갈려 있어야 한다.
    @Test
    void 정상_케이스의_재고_모양이_서로_다르다() {
        var urgentCounts = cases.stream().filter(c -> !c.isNegative())
                .map(c -> c.snapshot().rows().stream()
                        .filter(r -> r.daysToStockout() != null && r.daysToStockout() < 3).count())
                .distinct().toList();

        assertThat(urgentCounts).hasSizeGreaterThan(2);
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingEvalCaseLoadTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class BriefingEvalCase`

- [ ] **Step 4: 케이스 타입과 로더를 만든다**

`src/test/java/com/jhg/wms/eval/BriefingEvalCase.java`:

```java
package com.jhg.wms.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jhg.wms.domain.BriefingSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * 브리핑 평가셋 한 건. 두 종류다.
 *
 * <p><b>정상</b>: 스냅샷만 있고 {@code body}가 null이다. 러너가 생성한다.
 * <p><b>네거티브</b>: 사람이 손으로 망가뜨린 {@code body}가 있고 {@code failingItem}이
 * "이 항목이 떨어져야 한다"를 가리킨다. 생성하지 않는다.
 *
 * <p>네거티브 본문을 모델에게 쓰게 하지 않는 이유: 무엇이 망가졌는지를 모델이 정하게 되고,
 * 그러면 judge를 같은 모델의 판단으로 검증하는 순환이 된다. 사람이 쓴 본문이라야 정답을 안다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BriefingEvalCase(String id, BriefingSnapshot snapshot,
                               String body, String failingItem, String note) {

    public boolean isNegative() {
        return failingItem != null && !failingItem.isBlank();
    }

    public static List<BriefingEvalCase> loadAll(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (var in = BriefingEvalCase.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("평가셋 없음: " + resourcePath);
            Map<String, List<BriefingEvalCase>> root =
                    mapper.readValue(in, new TypeReference<>() {});
            return root.get("cases");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

**주의:** `EvalCase.loadAll`이 이미 있다. 그 파일을 열어 **같은 방식으로 맞춘다** — 루트 키가 `cases`가 아닐 수 있고, `ObjectMapper` 설정이 다를 수 있다.

- [ ] **Step 5: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingEvalCaseLoadTest*'`
Expected: PASS (5건)

`정상_케이스의_재고_모양이_서로_다르다`가 실패하면 평가셋이 게으른 것이다. **테스트를 고치지 말고 평가셋을 고친다.**

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/BriefingEvalCase.java \
        src/test/java/com/jhg/wms/eval/BriefingEvalCaseLoadTest.java \
        src/test/resources/eval/briefing-cases.json
git commit -m "$(cat <<'EOF'
test(wms): 브리핑 평가셋 12건을 짠다 (정상 8 + 네거티브 4)

네거티브 본문은 사람이 손으로 망가뜨렸다. 모델에게 "망가진 브리핑을 써라"라고 시키면 무엇이
망가졌는지를 모델이 정하게 되고, judge를 같은 모델의 판단으로 검증하는 순환이 된다.
정답을 아는 케이스는 이것뿐이라 judge가 고장났는지 가릴 유일한 수단이다.

정상 8건은 재고 모양이 서로 다르다 — 소진 임박이 여럿·하나도 없음·일평균 0 섞임·직전 발주
없음·직전 발주가 최근·얇은 표본·상품 하나. 같은 모양을 여덟 번 재면 표본이 하나다.
그게 지켜지는지도 로드 테스트가 본다.

failingItem 오타는 그 네거티브를 영원히 통과시키므로 로드 테스트가 값을 검사한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 러너와 리포트

**Files:**
- Create: `src/test/java/com/jhg/wms/eval/BriefingReportWriter.java`
- Create: `src/test/java/com/jhg/wms/eval/BriefingEvalTest.java`
- Test: `src/test/java/com/jhg/wms/eval/BriefingReportWriterTest.java`

**Interfaces:**
- Consumes: `BriefingEvalCase`(Task 7), `GroundingScorer.Result`(Task 5), `BriefingJudge`(Task 6), `EvalCase`·`EvalObservation`·`EvalAggregator`(기존, 손대지 않는다)
- Produces: `BriefingReportWriter.render(...)` → `String`

**집계 재사용이 이 태스크의 핵심이다.** 체크리스트 항목 하나는 값이 `YES`/`NO` 둘뿐인 분류이므로 `EvalAggregator.toCaseResult`가 그대로 쓰인다. 케이스 하나당 항목마다 `EvalCase`를 하나씩 만든다:

```java
new EvalCase(caseId + ":" + item, "", 기대값, note)   // 기대값은 "YES" 또는 "NO"
new EvalObservation(caseId + ":" + item, 관측값, null, null, in, out, model)
```

`EvalObservation`의 `confidence`는 `null`이다 — judge는 신뢰도를 내지 않는다. **`EvalAggregator.summarize`가 `confidence`를 `EnumMap`에 넣으므로 `null`이면 터진다.** 그래서 `summarize`를 쓰지 않고 `toCaseResult`만 쓴다. 집계는 `BriefingReportWriter`가 직접 센다.

- [ ] **Step 1: 리포트 테스트를 쓴다**

`src/test/java/com/jhg/wms/eval/BriefingReportWriterTest.java`:

```java
package com.jhg.wms.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingReportWriterTest {

    private BriefingReportWriter.CaseScore score(String id, boolean negative,
                                                 int total, int ungrounded,
                                                 Map<String, String> itemMajority,
                                                 List<String> unstableItems) {
        return new BriefingReportWriter.CaseScore(id, negative, total, ungrounded,
                itemMajority, unstableItems, "판정 근거 없음");
    }

    // 네거티브 절이 맨 위다. judge가 망가진 브리핑을 통과시키면 아래 표는 읽을 가치가 없다.
    @Test
    void 네거티브_절이_사실성_절보다_먼저_나온다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("neg-reasoned", true, 0, 0, Map.of("REASONED", "NO"), List.of())));

        assertThat(report.indexOf("## 네거티브")).isLessThan(report.indexOf("## 사실성"));
    }

    @Test
    void 환각이_있는_브리핑_수를_센다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("pos-01", false, 10, 2, Map.of("REASONED", "YES"), List.of()),
                score("pos-02", false, 8, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).contains("환각 있는 브리핑 **1/2**");
    }

    // 종합 점수를 만들지 않는다. 만들면 어느 쪽이 나빠서 떨어졌는지 못 읽는다.
    @Test
    void 종합_점수를_내지_않는다() {
        String report = BriefingReportWriter.render("claude-haiku-4-5", 3, List.of(
                score("pos-01", false, 10, 0, Map.of("REASONED", "YES"), List.of())));

        assertThat(report).doesNotContain("종합");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingReportWriterTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class BriefingReportWriter`

- [ ] **Step 3: 리포트 작성기를 만든다**

`src/test/java/com/jhg/wms/eval/BriefingReportWriter.java`:

```java
package com.jhg.wms.eval;

import java.time.LocalDate;
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

    private BriefingReportWriter() {}

    /**
     * @param itemMajority 항목명 → 다수결("YES"/"NO"/null). 네거티브는 지정 항목 하나만 담긴다.
     * @param unstableItems 3회가 갈린 항목들. judge가 흔들린다는 신호다.
     */
    public record CaseScore(String id, boolean negative, int numbersTotal, int numbersUngrounded,
                            Map<String, String> itemMajority, List<String> unstableItems, String note) {}

    public static String render(String model, int repeats, List<CaseScore> scores) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 발주 브리핑 평가 — ").append(LocalDate.now()).append("\n\n");
        sb.append("- 모델: `").append(model).append("`\n");
        sb.append("- 반복: judge ").append(repeats).append("회\n\n");

        // ── 네거티브가 먼저다 ──
        List<CaseScore> negatives = scores.stream().filter(CaseScore::negative).toList();
        sb.append("## 네거티브 케이스 — judge가 떨어뜨렸는가\n\n");
        sb.append("여기가 통과 못 하면 아래 두 표를 믿지 않는다.\n\n");
        sb.append("| id | 떨어져야 할 항목 | 다수결 | 판정 |\n|---|---|---|---|\n");
        for (CaseScore c : negatives) {
            String item = c.itemMajority().keySet().stream().findFirst().orElse("-");
            String majority = c.itemMajority().values().stream().findFirst().orElse("-");
            boolean caught = "NO".equals(majority) || "GROUNDING".equals(item) && c.numbersUngrounded() > 0;
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
        sb.append("| id | 숫자 | 근거 없음 |\n|---|---|---|\n");
        for (CaseScore c : positives) {
            sb.append("| `").append(c.id()).append("` | ").append(c.numbersTotal())
              .append(" | ").append(c.numbersUngrounded()).append(" |\n");
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

        return sb.toString();
    }
}
```

- [ ] **Step 4: 테스트 통과를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*BriefingReportWriterTest*'`
Expected: PASS (3건)

- [ ] **Step 5: 러너를 만든다**

`src/test/java/com/jhg/wms/eval/BriefingEvalTest.java`:

```java
package com.jhg.wms.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudePurchaseOrderBriefingGenerator;
import com.jhg.wms.service.PurchaseOrderBriefingGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브리핑 생성의 품질 평가. 구조는 {@link ClassificationEvalTest}와 같고 다른 것은 채점이다 —
 * 정답 라벨이 없으므로 기계 채점(숫자 대조)과 judge를 나눠 쓴다.
 *
 * <p>점수로 실패하지 않는 것은 같다. 실패 조건은 오직 "러너가 못 돌았다"이다.
 */
@Tag("eval")
class BriefingEvalTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final int JUDGE_REPEATS = 3;
    private static final Path REPORT = Path.of("build/reports/briefing-eval.md");

    @Test
    void 브리핑_품질을_재고_리포트를_남긴다() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY 미설정 — 평가를 건너뜁니다.");

        var client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey).timeout(Duration.ofSeconds(60)).maxRetries(1).build();
        var objectMapper = new ObjectMapper();
        PurchaseOrderBriefingGenerator generator =
                new ClaudePurchaseOrderBriefingGenerator(client, MODEL, 2048L);
        BriefingJudge judge = new BriefingJudge(client, objectMapper, MODEL, 1024L);

        List<BriefingEvalCase> cases = BriefingEvalCase.loadAll("eval/briefing-cases.json");
        List<BriefingReportWriter.CaseScore> scores = new ArrayList<>();
        String 실제모델 = MODEL;

        for (BriefingEvalCase c : cases) {
            // 네거티브는 생성하지 않는다. 본문이 이미 있다.
            String body = c.isNegative() ? c.body()
                    : generator.generate(c.snapshot()).map(PurchaseOrderBriefingGenerator.Briefing::body)
                            .orElse(null);
            if (body == null) {
                scores.add(new BriefingReportWriter.CaseScore(c.id(), c.isNegative(), 0, 0,
                        Map.of(), List.of(), "생성 실패"));
                continue;
            }

            var grounding = GroundingScorer.score(body, c.snapshot());

            // GROUNDING 네거티브는 judge에게 보내지 않는다 — 기계가 잡아야 하고 호출이 필요 없다.
            Map<String, String> majority = new LinkedHashMap<>();
            List<String> unstable = new ArrayList<>();
            if (!"GROUNDING".equals(c.failingItem())) {
                List<Map<String, Boolean>> verdicts = new ArrayList<>();
                for (int i = 0; i < JUDGE_REPEATS; i++) {
                    Optional<BriefingJudge.Verdict> v = judge.judge(body, c.snapshot());
                    if (v.isPresent()) {
                        verdicts.add(v.get().items());
                        실제모델 = v.get().model();
                    }
                }
                List<String> items = c.isNegative() ? List.of(c.failingItem()) : BriefingJudge.ITEMS;
                for (String item : items) {
                    // 항목 하나 = 값이 YES/NO 둘뿐인 분류. 기존 집계를 그대로 쓴다.
                    var source = new EvalCase(c.id() + ":" + item, "", "YES", c.note());
                    var observations = verdicts.stream()
                            .map(v -> new EvalObservation(source.id(),
                                    Boolean.TRUE.equals(v.get(item)) ? "YES" : "NO",
                                    null, null, 0, 0, MODEL))
                            .toList();
                    var result = EvalAggregator.toCaseResult(source, observations);
                    majority.put(item, result.majority());
                    if (result.unstable()) unstable.add(item);
                }
            } else {
                majority.put("GROUNDING", grounding.clean() ? "YES" : "NO");
            }

            scores.add(new BriefingReportWriter.CaseScore(c.id(), c.isNegative(),
                    grounding.total(), grounding.ungrounded().size(), majority, unstable,
                    c.note() == null ? "" : c.note()));
        }

        String report = BriefingReportWriter.render(실제모델, JUDGE_REPEATS, scores);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        assertThat(scores).hasSize(cases.size());
    }
}
```

**주의:** `EvalObservation`의 `confidence`에 `null`을 넣는다. `EvalAggregator.toCaseResult`는 `confidence`를 안 보므로 문제없지만 **`summarize`는 `EnumMap`에 넣어 터진다.** 그래서 위 러너는 `summarize`를 부르지 않는다. 부르고 싶어지면 `EvalAggregator`를 고치지 말고 이유를 다시 볼 것.

- [ ] **Step 6: 컴파일만 확인한다 (아직 안 돌린다 — 과금된다)**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 전체 테스트로 회귀를 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL. `@Tag("eval")`이라 `BriefingEvalTest`는 안 돈다.

- [ ] **Step 8: 커밋**

```bash
git add src/test/java/com/jhg/wms/eval/BriefingReportWriter.java \
        src/test/java/com/jhg/wms/eval/BriefingReportWriterTest.java \
        src/test/java/com/jhg/wms/eval/BriefingEvalTest.java
git commit -m "$(cat <<'EOF'
test(wms): 브리핑 평가 러너와 리포트를 만든다

집계를 새로 짜지 않았다. 체크리스트 항목 하나는 값이 YES/NO 둘뿐인 분류라
EvalAggregator.toCaseResult가 그대로 쓰인다. 2026-09-09에 범주를 ReturnCategory에서 String으로
떼어낸 것이 여기서 값을 했다 — 그때 목적은 메모 분류였는데 도메인 enum이 아닌 값도 들어간다.

공짜로 따라온 것이 하나 있다. 흔들림 판정이 곧 judge 신뢰성 관측이 된다. 같은 브리핑을 세 번
채점했는데 항목이 갈리면 브리핑이 애매한 게 아니라 judge가 흔들리는 것일 수 있다.

summarize는 부르지 않는다. judge는 신뢰도를 내지 않아 confidence가 null인데 summarize는
EnumMap에 넣어 터진다. EvalAggregator를 고치는 대신 안 부르는 쪽을 골랐다 — 반품·메모 평가의
정상 경로를 건드릴 이유가 없다.

리포트는 네거티브 절이 맨 위다. judge가 망가진 브리핑을 통과시키면 아래 두 표는 읽을 가치가
없다. 종합 점수는 만들지 않는다 — 만들면 어느 쪽이 나빠서 떨어졌는지 못 읽는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: 1회차 측정과 기록

**Files:**
- Create: `docs/wms-briefing-eval.md`
- Modify: `README.md`(V10.0 절 아래에 브리핑 문단), `docs/superpowers/specs/v11/2026-09-10-purchase-order-briefing-design.md`(개정 이력)
- Modify: `.superpowers/sdd/progress.md`

- [ ] **Step 1: 1회차를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew evalTest --tests '*BriefingEvalTest*'
```

**필터를 반드시 건다.** 안 걸면 반품(41건)·메모(30건) 평가가 같이 나가 455원이 더 든다. 반품 프롬프트는 동결 상태라 다시 잴 이유가 없다.

- [ ] **Step 2: 네거티브 절부터 읽는다**

**judge가 네 건을 다 잡았는가?**

- 다 잡았으면 → 사실성·유용성 표를 읽는다
- **하나라도 놓쳤으면 → 유용성 표를 읽지 않는다.** judge 프롬프트의 그 항목이 무르다는 뜻이다. 항목 문구를 고치고 다시 잰다(157원). 고친 것과 그 이유를 원장에 적는다.

- [ ] **Step 3: 자기편향을 관측한다**

judge 모델만 바꿔 한 번 더 돌린다. `BriefingEvalTest`의 `new BriefingJudge(client, objectMapper, MODEL, 1024L)`에서 `MODEL`을 `"claude-sonnet-5"`로 바꾼다.

**한 번에 하나만 바꾼다** — 생성은 haiku 그대로 두고 judge만 바꾼다. 통과율이 갈리면 그 차이가 자기편향의 크기다. 안 갈리면 "이 셋에서는 관측되지 않았다"이지 "없다"가 아니다.

측정 후 `MODEL`로 되돌린다.

- [ ] **Step 4: 리포트를 문서로 옮긴다**

`docs/wms-briefing-eval.md`를 만든다. 구조는 `docs/wms-purchase-order-memo-eval.md`를 따른다 — 머리말(측정 방법·라벨 권위·모델 스냅샷 주의) → 회차별 절 → "이 회차가 답한 것".

**"이 회차가 답한 것"에 스펙의 성공 기준 네 질문을 답한다:**

1. 모델이 준 숫자를 그대로 쓰는가, 지어내는가? (지표 ①·② 실측치)
2. judge가 망가진 브리핑을 떨어뜨리는가?
3. judge는 자기 출력에 후한가? (Step 3의 차이)
4. 기계 채점과 judge 중 어느 쪽이 더 흔들리는가?

**1회 관측에 원인 설명을 붙이지 않는다.** 메모 1회차에서 두 오답을 한 원인으로 묶었다가 2회차에 갈라졌다 — 숫자만이 아니라 설명 문장에도 적용된다.

- [ ] **Step 5: 환각률을 보고 구조를 정한다**

이 작업의 원래 질문이다. 스펙 "거부한 것" 절의 구조화 출력 안을 지금 다시 판단한다.

- **환각이 거의 없으면** → 지금 형태를 유지한다. "쟀더니 안 나더라"가 근거로 남는다.
- **환각이 유의하면** → 구조화 출력으로 바꿀지 정한다. **다만 이 회차에서 바로 바꾸지 않는다** — 1회 관측이다. 2회차를 재서 재현되는지 먼저 본다.

**어느 쪽이든 판단과 근거를 원장에 적는다.** 적지 않으면 다음 사람이 같은 질문을 처음부터 다시 한다.

- [ ] **Step 6: README와 스펙을 현행화한다**

`README.md`의 V10.0 절 맨 아래(메모 분류 품질 문장 다음)에 문단을 더한다:

```markdown
**발주 브리핑**(V11.0)은 버튼을 누르면 근거 패널 상위 5개를 읽어 문단 하나를 생성합니다.
분류 둘과 달리 동기이고 구조화 출력이 없습니다 — 문단이라 가둘 스키마가 없습니다.
참고 표시 전용이며 발주 내용에 영향을 주지 않습니다.
품질은 12건(정상 8 + 네거티브 4) 평가셋으로 측정합니다 — [측정 결과](docs/wms-briefing-eval.md).
```

스펙 문서 맨 아래에 개정 이력을 더한다:

```markdown
## 개정 이력 (2026-09-10)

- **judge 프롬프트 위치**: 스펙은 `src/main/resources/prompts/`라고 적었으나 구현은
  `src/test/resources/prompts/`에 뒀다. judge는 평가 전용이라 운영 jar에 실릴 이유가 없다.
- **`GROUNDING` 네거티브는 judge를 부르지 않는다**: 스펙은 네거티브 4건을 모두 judge에
  보내는 것처럼 읽혔다. 숫자 네거티브는 `GroundingScorer`가 잡아야 하고 API 호출이 필요 없다.
  회당 judge 호출은 3건 × 3회 = 9회다.
- **`EvalAggregator.summarize`는 안 쓴다**: judge가 신뢰도를 내지 않아 `confidence`가 null인데
  `summarize`는 `EnumMap`에 넣어 터진다. `toCaseResult`만 재사용한다.
- **1회차 실비**: [여기에 실측치를 적는다]
```

- [ ] **Step 7: 원장에 2부를 적고 커밋한다**

`.superpowers/sdd/progress.md`에 append. **반드시 담을 것:**

- 네거티브 절 결과(judge가 다 잡았는가) — 이게 나머지를 읽을 자격을 준다
- 환각률 실측치와 Step 5의 판단
- 자기편향 관측 결과
- 실비
- **거부한 것**: 종합 점수, judge에게 숫자 묻기, `EvalAggregator` 수정

```bash
git add -A
git commit -m "$(cat <<'EOF'
test(wms): 발주 브리핑 평가 1회차를 재고 결과를 남긴다

[여기에 실측 결과를 요약한다 — 네거티브 판정, 환각률, 자기편향, 실비]

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 8: PR을 열고 병합한다**

병합 전에 `git log --oneline origin/master..master`로 로컬 master 오염을 먼저 본다 — 이 저장소를 다른 세션이 함께 잡는다.

---

# 자체 검토 결과

**스펙 대비 누락 없음.** 스펙의 각 절과 태스크 대응: 엔드포인트·동기·타임아웃(Task 2·4), 입력 상위 5개(Task 3), `input_snapshot`(Task 1·3), 프롬프트(Task 2), 채점기 1(Task 5), 채점기 2(Task 6), 네거티브·자기편향(Task 7·9), 리포트 순서(Task 8), 하네스 재사용(Task 8), 비용·규율·성공 기준(Task 9).

**스펙에서 고친 것 셋** — Task 9 Step 6에서 스펙 개정 이력에 적는다:
1. judge 프롬프트는 test 리소스에 둔다
2. `GROUNDING` 네거티브는 judge를 안 부른다 (회당 judge 호출 9회)
3. `EvalAggregator.summarize`는 안 쓴다 (`confidence`가 null이라 터진다)

**타입 일관성 확인:** `BriefingSnapshot.Row`의 필드명이 Task 1(정의) · Task 2(렌더) · Task 5(채점) · Task 7(평가셋 JSON)에서 같다. `BriefingJudge.ITEMS`의 세 항목명이 Task 6(정의) · Task 7(로드 테스트) · Task 8(리포트)에서 같다.

**남은 위험 둘 — 실행 중 확인할 것:**
- Task 6의 `toSchema`는 SDK 버전에 따라 빌더 모양이 다르다. `ClaudePurchaseOrderMemoClassifier`의 실제 구현을 열어 그대로 쓴다.
- Task 4의 MockMvc 셋업은 이 저장소의 기존 web 테스트 방식을 따라야 한다. 먼저 확인하고 맞춘다.
