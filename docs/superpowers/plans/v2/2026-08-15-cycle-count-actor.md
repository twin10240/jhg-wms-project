# 재고 실사 + 원장 행위자 구현 계획 (WMS V2.1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실사 세션으로 장부와 실물의 차이를 발견·승인해 `COUNT` 원장으로 반영하고, 모든 원장 행에 행위자를 남긴다.

**Architecture:** 실사 세션(`CycleCount`)은 `OPEN → SUBMITTED → APPROVED/REJECTED`로 전이하며, 승인 시 품목별로 `실물 − 승인 시점 장부`를 계산해 차이가 있는 품목만 기존 `InventoryService.applyDelta`로 반영한다. 재고 증가 경로가 하나뿐이라 OMS 백오더 승격 통지가 자동으로 따라온다. 행위자는 `ActorProvider`(SecurityContext 래퍼)를 원장 기록 지점 두 곳에서만 호출해 채운다.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Data JPA, Thymeleaf, Spring Security, H2(dev)/PostgreSQL(prod), JUnit 5 + AssertJ + Mockito, Gradle.

## Global Constraints

- 스펙: `docs/superpowers/specs/v2/2026-08-15-cycle-count-actor-design.md`
- 브랜치: `feat/wms-v2.1` (이미 생성됨, 스펙 커밋 `6c8c882`)
- 빌드/테스트: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
- 시작 시점 테스트 206건 전부 통과. 각 태스크 종료 시 전체 그린 유지.
- 차이 = **실물 − 승인 시점 장부**. `bookQtyAtOpen`은 표시 전용이며 계산에 쓰지 않는다.
- `countedQty`는 0 이상. `0`(세어보니 없음)과 `null`(미입력)을 구분한다.
- 화면에 enum 원문을 노출하지 않는다. 상태는 한글(작성 중 / 승인 대기 / 승인 / 반려).
- 커밋 메시지는 한국어, 본문에 "왜"를 적는다. 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- 새 enum 상수 추가 시 prod Postgres check 제약 갱신이 필요하다(`docs/wms-enum-schema-migration.md`).

---

### Task 1: 원장 행위자 — `ActorProvider`와 `actor` 컬럼

**Files:**
- Create: `src/main/java/com/jhg/wms/config/ActorProvider.java`
- Create: `src/main/java/com/jhg/wms/config/SecurityContextActorProvider.java`
- Modify: `src/main/java/com/jhg/wms/domain/InventoryTransaction.java`
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java`
- Test: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java` (기존 파일 — 생성자 변경 반영 + 케이스 추가)
- Test: `src/test/java/com/jhg/wms/config/SecurityContextActorProviderTest.java`

**Interfaces:**
- Produces: `ActorProvider.current(): String` — 인증된 사용자명, 없거나 익명이면 `"system"`
- Produces: `InventoryTransaction.of(Long productId, InventoryTransactionType type, int delta, int beforeQty, int afterQty, String reference, String reason, String actor)` — **인자 8개로 변경**
- Produces: `InventoryTransaction.getActor(): String`
- Produces: `new InventoryService(InventoryRepository, ReservationRepository, InventoryTransactionRepository, OmsReplenishmentNotifier, ActorProvider)` — **생성자 인자 5개로 변경**

- [ ] **Step 1: `ActorProvider` 구현체의 실패 테스트를 쓴다**

Create `src/test/java/com/jhg/wms/config/SecurityContextActorProviderTest.java`:

```java
package com.jhg.wms.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextActorProviderTest {

    private final SecurityContextActorProvider provider = new SecurityContextActorProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자는_사용자명을_돌려준다() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_MANAGER")));

        assertThat(provider.current()).isEqualTo("manager");
    }

    // 기동 시드·백필은 인증 컨텍스트가 없다 — 원장에 빈 값이 아니라 "system"이 남아야 구분된다.
    @Test
    void 인증이_없으면_system() {
        assertThat(provider.current()).isEqualTo("system");
    }

    @Test
    void 익명_인증도_system() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(provider.current()).isEqualTo("system");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*SecurityContextActorProviderTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class SecurityContextActorProvider`

- [ ] **Step 3: `ActorProvider`와 구현체를 만든다**

Create `src/main/java/com/jhg/wms/config/ActorProvider.java`:

```java
package com.jhg.wms.config;

/** 원장에 남길 행위자. 사람이면 사용자명, 서버간 호출이면 서비스 계정명, 그 외는 "system". */
public interface ActorProvider {
    String current();
}
```

Create `src/main/java/com/jhg/wms/config/SecurityContextActorProvider.java`:

```java
package com.jhg.wms.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** SecurityContext를 얇게 감싼다 — 서비스가 Spring Security를 직접 알지 않게 하고,
 *  테스트에서 가짜 구현으로 갈아끼울 수 있게 하려는 목적. */
@Component
public class SecurityContextActorProvider implements ActorProvider {

    public static final String SYSTEM = "system";

    @Override
    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            return SYSTEM;
        return auth.getName();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*SecurityContextActorProviderTest*'`
Expected: PASS (3건)

- [ ] **Step 5: 원장에 행위자가 남는지 검증하는 실패 테스트를 쓴다**

Modify `src/test/java/com/jhg/wms/service/InventoryServiceTest.java` — `setUp()`을 아래로 바꾸고 테스트 2건을 파일 끝(마지막 `}` 앞)에 추가한다:

```java
    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        service = new InventoryService(repo, reservationRepo, adjustmentRepo, notifier, () -> "manager");
    }
```

```java
    // 접근제어를 롤로 나눠뒀는데 원장에 누가 했는지가 없으면 감사 질문에 답할 수 없다.
    @Test
    void applyDelta는_행위자를_원장에_남긴다() {
        seed(1L, 10);

        service.applyDelta(1L, 5, InventoryTransactionType.RECEIVE, "PO#1", null);

        assertThat(adjustmentRepo.findAll())
                .extracting(com.jhg.wms.domain.InventoryTransaction::getActor)
                .containsExactly("manager");
    }

    @Test
    void 출고도_행위자를_남긴다() {
        seed(1L, 10);
        service.reserveAll(1L, Map.of(1L, 3));
        service.shipAll(1L, Map.of(1L, 3));

        assertThat(adjustmentRepo.findAll())
                .filteredOn(t -> t.getType() == InventoryTransactionType.SHIP)
                .extracting(com.jhg.wms.domain.InventoryTransaction::getActor)
                .containsExactly("manager");
    }
```

- [ ] **Step 6: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*InventoryServiceTest*'`
Expected: 컴파일 실패 — `InventoryService` 생성자 인자 수 불일치, `getActor()` 없음

- [ ] **Step 7: 엔티티에 `actor`를 추가한다**

Modify `src/main/java/com/jhg/wms/domain/InventoryTransaction.java` — `reason` 필드 아래에 필드를 추가하고 팩토리를 교체한다:

```java
    /** 이 행을 만든 주체. 사람이면 사용자명, OMS 서버간 호출이면 서비스 계정명, 기동 시드는 "system".
     *  nullable: 이 필드가 생기기 전 행은 알 수 없는 정보라 백필하지 않는다. */
    private String actor;
```

```java
    public static InventoryTransaction of(Long productId, InventoryTransactionType type, int delta,
                                          int beforeQty, int afterQty, String reference, String reason,
                                          String actor) {
        InventoryTransaction t = new InventoryTransaction();
        t.productId = productId;
        t.type = type;
        t.delta = delta;
        t.beforeQty = beforeQty;
        t.afterQty = afterQty;
        t.reference = reference;
        t.reason = reason;
        t.actor = actor;
        t.createdAt = LocalDateTime.now();
        return t;
    }
```

- [ ] **Step 8: 서비스가 행위자를 채우게 한다**

Modify `src/main/java/com/jhg/wms/service/InventoryService.java`:

1. import 추가: `import com.jhg.wms.config.ActorProvider;`
2. 필드 추가(다른 `private final` 필드 아래):

```java
    private final ActorProvider actorProvider;
```

3. `applyDelta` 안의 원장 저장 줄을 교체:

```java
        transactionRepository.save(InventoryTransaction.of(productId, type, delta, before, after,
                reference, reason, actorProvider.current()));
```

4. `shipAll` 안의 원장 저장 줄을 교체:

```java
            transactionRepository.save(InventoryTransaction.of(
                pid, InventoryTransactionType.SHIP, -qty, before, inv.getOnHandQty(),
                "ORDER#" + orderId, null, actorProvider.current()));
```

`@RequiredArgsConstructor`를 쓰고 있으므로 생성자는 자동으로 5인자가 된다.

- [ ] **Step 9: 전체 테스트를 돌린다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: PASS. 실패하면 대부분 다른 테스트가 `new InventoryService(...)`를 4인자로 부르고 있는 경우다 — 그 호출에도 `() -> "system"`을 마지막 인자로 넣는다.

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/jhg/wms/config/ActorProvider.java \
        src/main/java/com/jhg/wms/config/SecurityContextActorProvider.java \
        src/main/java/com/jhg/wms/domain/InventoryTransaction.java \
        src/main/java/com/jhg/wms/service/InventoryService.java \
        src/test/java/com/jhg/wms/config/SecurityContextActorProviderTest.java \
        src/test/java/com/jhg/wms/service/InventoryServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 재고 원장에 행위자 기록

OPERATOR/MANAGER로 접근제어를 나눠뒀는데 정작 원장은 누가 바꿨는지를 남기지 않았다.
"이 수량이 왜 이렇게 됐는지 역추적할 수 있다"는 주장이 사람이 개입한 조정에서 반쪽이었다.

SecurityContext 조회를 ActorProvider로 감싸 원장 기록 지점 두 곳(applyDelta, shipAll)에서만
부른다. 호출부는 바뀌지 않고, 서비스도 Spring Security를 직접 알지 않는다.
사람은 사용자명, OMS 서버간 호출은 서비스 계정명, 기동 시드는 "system"으로 구분된다.

기존 행은 백필하지 않는다 — 과거에 누가 했는지는 알 수 없는 정보다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 재고 이력 화면에 행위자 열

**Files:**
- Modify: `src/main/resources/templates/admin/inventory-transactions.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: `InventoryTransaction.getActor()` (Task 1)

- [ ] **Step 1: 실패 테스트를 쓴다**

Modify `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` — 파일 끝(마지막 `}` 앞)에 추가한다:

```java
    // 원장에 행위자를 남겨도 화면에 없으면 감사에 쓸 수 없다. 값이 없는 과거 행은 "—"로 구분해 보인다.
    @Test
    void 이력화면은_행위자를_보여주고_없으면_대시로_표시한다() throws Exception {
        InventoryTransaction withActor = InventoryTransaction.of(
                1L, InventoryTransactionType.ADJUST, 3, 10, 13, null, "파손 정정", "manager");
        InventoryTransaction legacy = InventoryTransaction.of(
                1L, InventoryTransactionType.ADJUST, 1, 13, 14, null, "구 데이터", null);
        when(inventoryService.findTransactions(eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(withActor, legacy)));

        mockMvc.perform(get("/admin/inventory/transactions").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("행위자")))
                .andExpect(content().string(containsString("manager")))
                .andExpect(content().string(containsString("—")));
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'`
Expected: FAIL — 응답에 `행위자`가 없음

- [ ] **Step 3: 템플릿에 열을 추가한다**

Modify `src/main/resources/templates/admin/inventory-transactions.html` — 표의 `<thead>` 마지막 `<th>` 뒤에 헤더를 추가하고, `<tbody>`의 `th:each` 행 마지막 `<td>` 뒤에 셀을 추가한다:

```html
        <th>행위자</th>
```

```html
          <td th:text="${txn.actor != null ? txn.actor : '—'}">—</td>
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/templates/admin/inventory-transactions.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 재고 이력 화면에 행위자 열

원장에 남긴 행위자를 화면에서 볼 수 있게 한다. 이 필드가 생기기 전 행은 값이 없으므로
빈칸이 아니라 "—"로 표시해 "모르는 것"과 "비어 있는 것"을 구분한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 실사 도메인 — 상태 전이와 입력 규칙

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/CycleCountStatus.java`
- Create: `src/main/java/com/jhg/wms/domain/CycleCount.java`
- Create: `src/main/java/com/jhg/wms/domain/CycleCountItem.java`
- Test: `src/test/java/com/jhg/wms/domain/CycleCountTest.java`

**Interfaces:**
- Produces: `CycleCountStatus { OPEN, SUBMITTED, APPROVED, REJECTED }`
- Produces: `CycleCount.open(String actor, String memo): CycleCount`
- Produces: `CycleCount.addItem(Long productId, int bookQtyAtOpen): void`
- Produces: `CycleCount.recordCount(Long itemId, Integer countedQty): void`
- Produces: `CycleCount.submit(String actor): void`
- Produces: `CycleCount.approve(String actor): void`
- Produces: `CycleCount.reject(String actor, String reason): void`
- Produces: `CycleCount.getItems(): List<CycleCountItem>`, `getStatus()`, `getMemo()`
- Produces: `CycleCountItem.getProductId()`, `getBookQtyAtOpen()`, `getCountedQty(): Integer`, `getId()`

- [ ] **Step 1: 실패 테스트를 쓴다**

Create `src/test/java/com/jhg/wms/domain/CycleCountTest.java`:

```java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CycleCountTest {

    private CycleCount opened() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        c.addItem(2L, 30);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        ReflectionTestUtils.setField(c.getItems().get(1), "id", 102L);
        return c;
    }

    @Test
    void 세션을_열면_OPEN이고_실물수량은_비어있다() {
        CycleCount c = opened();

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.OPEN);
        assertThat(c.getItems()).extracting(CycleCountItem::getCountedQty).containsOnlyNulls();
        assertThat(c.getItems()).extracting(CycleCountItem::getBookQtyAtOpen).containsExactly(15, 30);
    }

    // 0은 "세어보니 없었다"는 유효한 결과다. 미입력(null)과 섞이면 안 센 품목이 조용히 확정된다.
    @Test
    void 실물수량_0은_유효한_입력이다() {
        CycleCount c = opened();

        c.recordCount(101L, 0);

        assertThat(c.getItems().get(0).getCountedQty()).isZero();
    }

    @Test
    void 실물수량은_음수일_수_없다() {
        CycleCount c = opened();

        assertThatThrownBy(() -> c.recordCount(101L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이상");
    }

    @Test
    void 미입력_품목이_있으면_제출할_수_없다() {
        CycleCount c = opened();
        c.recordCount(101L, 14);

        assertThatThrownBy(() -> c.submit("operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실물 수량");
        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.OPEN);
    }

    @Test
    void 전_품목_입력하면_제출된다() {
        CycleCount c = opened();
        c.recordCount(101L, 14);
        c.recordCount(102L, 30);

        c.submit("operator");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
        assertThat(c.getSubmittedBy()).isEqualTo("operator");
    }

    @Test
    void 제출된_세션은_실물수량을_고칠_수_없다() {
        CycleCount c = submitted();

        assertThatThrownBy(() -> c.recordCount(101L, 99))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void OPEN을_바로_승인할_수_없다() {
        CycleCount c = opened();

        assertThatThrownBy(() -> c.approve("manager"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 승인하면_APPROVED이고_승인자가_남는다() {
        CycleCount c = submitted();

        c.approve("manager");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.APPROVED);
        assertThat(c.getApprovedBy()).isEqualTo("manager");
    }

    @Test
    void 반려하면_REJECTED이고_사유가_남는다() {
        CycleCount c = submitted();

        c.reject("manager", "계수 오류로 재실사 필요");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.REJECTED);
        assertThat(c.getRejectReason()).isEqualTo("계수 오류로 재실사 필요");
    }

    // 종결 상태에서 다시 전이하면 "언제 확정됐는가"가 흐려진다.
    @Test
    void 승인된_세션은_다시_전이할_수_없다() {
        CycleCount c = submitted();
        c.approve("manager");

        assertThatThrownBy(() -> c.reject("manager", "번복")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.approve("manager")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 반려된_세션은_다시_전이할_수_없다() {
        CycleCount c = submitted();
        c.reject("manager", "재실사");

        assertThatThrownBy(() -> c.approve("manager")).isInstanceOf(IllegalStateException.class);
    }

    private CycleCount submitted() {
        CycleCount c = opened();
        c.recordCount(101L, 14);
        c.recordCount(102L, 30);
        c.submit("operator");
        return c;
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class CycleCount`

- [ ] **Step 3: 도메인 3개를 만든다**

Create `src/main/java/com/jhg/wms/domain/CycleCountStatus.java`:

```java
package com.jhg.wms.domain;

public enum CycleCountStatus {
    OPEN,       // 작성 중 — 실물 수량 입력·수정 가능
    SUBMITTED,  // 승인 대기 — 계수 완료, 장부는 아직 그대로
    APPROVED,   // 승인 — 차이가 COUNT 원장으로 반영됨(종결)
    REJECTED    // 반려 — 장부 불변(종결)
}
```

Create `src/main/java/com/jhg/wms/domain/CycleCountItem.java`:

```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cycle_count_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CycleCountItem {

    @Id @GeneratedValue
    @Column(name = "cycle_count_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_count_id", nullable = false)
    private CycleCount cycleCount;

    @Column(nullable = false)
    private Long productId;

    /** 세션을 연 시점의 장부 수량. 표시 전용 — 차이 계산은 승인 시점 장부로 다시 한다.
     *  이 값으로 계산하면 실사 중 이동분이 이중 반영된다. */
    @Column(nullable = false)
    private int bookQtyAtOpen;

    /** 실물 수량. null = 미입력. 0은 "세어보니 없었다"는 유효한 결과다. */
    private Integer countedQty;

    static CycleCountItem create(CycleCount cycleCount, Long productId, int bookQtyAtOpen) {
        CycleCountItem item = new CycleCountItem();
        item.cycleCount = cycleCount;
        item.productId = productId;
        item.bookQtyAtOpen = bookQtyAtOpen;
        return item;
    }

    void record(Integer countedQty) {
        if (countedQty == null)
            throw new IllegalArgumentException("실물 수량을 입력해야 합니다. productId=" + productId);
        if (countedQty < 0)
            throw new IllegalArgumentException("실물 수량은 0 이상이어야 합니다. productId=" + productId);
        this.countedQty = countedQty;
    }
}
```

Create `src/main/java/com/jhg/wms/domain/CycleCount.java`:

```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 실사 세션. 계수(OPERATOR)와 승인(MANAGER)을 분리해, 센 사람이 스스로 장부를 고치지 못하게 한다. */
@Entity
@Table(name = "cycle_count")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CycleCount {

    @Id @GeneratedValue
    @Column(name = "cycle_count_id")
    private Long id;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // H2 네이티브 ENUM 회피 — 값 추가 시 기존 컬럼이 거부하는 사고 방지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CycleCountStatus status;

    private String memo;

    @OneToMany(mappedBy = "cycleCount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CycleCountItem> items = new ArrayList<>();

    @Column(nullable = false)
    private String createdBy;
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private String submittedBy;
    private LocalDateTime submittedAt;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String rejectedBy;
    private LocalDateTime rejectedAt;
    private String rejectReason;

    public static CycleCount open(String actor, String memo) {
        CycleCount c = new CycleCount();
        c.status = CycleCountStatus.OPEN;
        c.memo = memo;
        c.createdBy = actor;
        c.createdAt = LocalDateTime.now();
        return c;
    }

    public void addItem(Long productId, int bookQtyAtOpen) {
        requireOpen();
        items.add(CycleCountItem.create(this, productId, bookQtyAtOpen));
    }

    public void recordCount(Long itemId, Integer countedQty) {
        requireOpen();
        items.stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 세션의 품목이 아닙니다. itemId=" + itemId))
                .record(countedQty);
    }

    public void submit(String actor) {
        requireOpen();
        boolean missing = items.stream().anyMatch(i -> i.getCountedQty() == null);
        if (missing)
            throw new IllegalArgumentException("모든 품목의 실물 수량을 입력해야 합니다.");
        status = CycleCountStatus.SUBMITTED;
        submittedBy = actor;
        submittedAt = LocalDateTime.now();
    }

    public void approve(String actor) {
        requireSubmitted();
        status = CycleCountStatus.APPROVED;
        approvedBy = actor;
        approvedAt = LocalDateTime.now();
    }

    public void reject(String actor, String reason) {
        requireSubmitted();
        status = CycleCountStatus.REJECTED;
        rejectedBy = actor;
        rejectedAt = LocalDateTime.now();
        rejectReason = reason;
    }

    private void requireOpen() {
        if (status != CycleCountStatus.OPEN)
            throw new IllegalStateException("작성 중인 실사에서만 할 수 있습니다.");
    }

    private void requireSubmitted() {
        if (status != CycleCountStatus.SUBMITTED)
            throw new IllegalStateException("승인 대기 상태에서만 승인·반려할 수 있습니다.");
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountTest*'`
Expected: PASS (11건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/CycleCount.java \
        src/main/java/com/jhg/wms/domain/CycleCountItem.java \
        src/main/java/com/jhg/wms/domain/CycleCountStatus.java \
        src/test/java/com/jhg/wms/domain/CycleCountTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 실사 세션 도메인 — 상태 전이와 입력 규칙

OPEN → SUBMITTED → APPROVED/REJECTED. 승인·반려는 종결 상태로, 되돌리면
"언제 확정됐는가"가 흐려진다. 반려된 계수는 재사용하지 않고 새 세션을 만든다.

실물 수량은 0과 null을 구분한다 — 0은 "세어보니 없었다"는 유효한 결과이고,
미입력을 0으로 받으면 안 센 품목이 조용히 확정된다(반품 검수에서 겪은 함정).

bookQtyAtOpen은 표시 전용이다. 차이는 승인 시점 장부로 다시 계산하므로
이 값으로 계산하면 실사 중 이동분이 이중 반영된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 리포지토리와 세션 생성·입력·제출

**Files:**
- Create: `src/main/java/com/jhg/wms/repository/CycleCountRepository.java`
- Create: `src/main/java/com/jhg/wms/service/CycleCountService.java`
- Test: `src/test/java/com/jhg/wms/service/CycleCountServiceTest.java`

**Interfaces:**
- Consumes: `CycleCount.open/addItem/recordCount/submit` (Task 3), `ActorProvider.current()` (Task 1)
- Produces: `CycleCountRepository.findById(Long)`(items 즉시 페치), `findAllByOrderByIdDesc()`, `findByStatusOrderByIdDesc(CycleCountStatus)`, `findOpenProductIds(): List<Long>`, `countByStatus(CycleCountStatus): long`
- Produces: `CycleCountService.open(List<Long> productIds, String memo): CycleCount`
- Produces: `CycleCountService.saveCounts(Long sessionId, Map<Long, Integer> countsByItemId): void`
- Produces: `CycleCountService.submit(Long sessionId): void`
- Produces: `CycleCountService.findById(Long): CycleCount`, `findAll(CycleCountStatus): List<CycleCount>`

- [ ] **Step 1: 실패 테스트를 쓴다**

Create `src/test/java/com/jhg/wms/service/CycleCountServiceTest.java`:

```java
package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest
class CycleCountServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired CycleCountRepository cycleCountRepo;
    @Autowired jakarta.persistence.EntityManager em;

    InventoryService inventoryService;
    CycleCountService service;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, txnRepo,
                mock(OmsReplenishmentNotifier.class), () -> "manager");
        service = new CycleCountService(cycleCountRepo, inventoryRepo, inventoryService, () -> "operator");
    }

    private void seed(long pid, int qty) {
        inventoryRepo.save(Inventory.create(pid, qty));
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    @Test
    void 세션을_열면_대상의_장부수량이_스냅샷으로_담긴다() {
        seed(1L, 15);
        seed(2L, 30);

        CycleCount c = service.open(List.of(1L, 2L), "8월 순환 실사");
        flush();

        CycleCount found = service.findById(c.getId());
        assertThat(found.getStatus()).isEqualTo(CycleCountStatus.OPEN);
        assertThat(found.getItems()).extracting("productId", "bookQtyAtOpen")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, 15),
                        org.assertj.core.groups.Tuple.tuple(2L, 30));
    }

    @Test
    void 대상이_없으면_거부한다() {
        assertThatThrownBy(() -> service.open(List.of(), "빈 실사"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대상");
    }

    @Test
    void 재고행이_없는_상품은_거부한다() {
        seed(1L, 15);

        assertThatThrownBy(() -> service.open(List.of(1L, 99L), "없는 상품"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // 나중 세션의 실물 수량은 이미 낡은 값이라, 겹치면 "센 시점"과 "적용 시점"이 어긋난 조정이 남는다.
    @Test
    void 열린_세션에_있는_상품은_새_세션에_담을_수_없다() {
        seed(1L, 15);
        seed(2L, 30);
        service.open(List.of(1L), "먼저 연 세션");
        flush();

        assertThatThrownBy(() -> service.open(List.of(1L, 2L), "겹치는 세션"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행 중인 실사");
    }

    @Test
    void 종결된_세션의_상품은_다시_담을_수_있다() {
        seed(1L, 15);
        CycleCount first = service.open(List.of(1L), "첫 세션");
        flush();
        service.saveCounts(first.getId(), Map.of(itemId(first, 1L), 15));
        service.submit(first.getId());
        flush();
        service.findById(first.getId()).reject("manager", "재실사");
        flush();

        CycleCount second = service.open(List.of(1L), "두 번째 세션");

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void 미입력_품목이_있으면_제출이_거부된다() {
        seed(1L, 15);
        seed(2L, 30);
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));

        assertThatThrownBy(() -> service.submit(c.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.OPEN);
    }

    @Test
    void 전_품목_입력_후_제출하면_승인대기가_된다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));

        service.submit(c.getId());
        flush();

        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
    }

    /** 화면은 itemId로 값을 보내므로 테스트도 productId → itemId로 변환해 쓴다. */
    private Long itemId(CycleCount session, long productId) {
        return service.findById(session.getId()).getItems().stream()
                .filter(i -> i.getProductId() == productId)
                .findFirst().orElseThrow().getId();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountServiceTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class CycleCountRepository`

- [ ] **Step 3: 리포지토리를 만든다**

Create `src/main/java/com/jhg/wms/repository/CycleCountRepository.java`:

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CycleCountRepository extends JpaRepository<CycleCount, Long> {

    @EntityGraph(attributePaths = "items")
    @Override
    Optional<CycleCount> findById(Long id);

    @EntityGraph(attributePaths = "items")
    List<CycleCount> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = "items")
    List<CycleCount> findByStatusOrderByIdDesc(CycleCountStatus status);

    long countByStatus(CycleCountStatus status);

    /** 아직 종결되지 않은 세션이 잡고 있는 상품들. 겹침 검사 한 번에 쓰려고 productId만 뽑는다. */
    @Query("SELECT DISTINCT i.productId FROM CycleCount c JOIN c.items i " +
           "WHERE c.status IN (com.jhg.wms.domain.CycleCountStatus.OPEN, " +
           "                   com.jhg.wms.domain.CycleCountStatus.SUBMITTED)")
    List<Long> findOpenProductIds();
}
```

- [ ] **Step 4: 서비스를 만든다**

Create `src/main/java/com/jhg/wms/service/CycleCountService.java`:

```java
package com.jhg.wms.service;

import com.jhg.wms.config.ActorProvider;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CycleCountService {

    private final CycleCountRepository cycleCountRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final ActorProvider actorProvider;

    @Transactional
    public CycleCount open(List<Long> productIds, String memo) {
        if (productIds == null || productIds.isEmpty())
            throw new IllegalArgumentException("실사 대상을 1개 이상 선택해야 합니다.");

        List<Long> distinct = productIds.stream().distinct().toList();
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(distinct).stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i));
        for (Long pid : distinct) {
            if (!inventories.containsKey(pid))
                throw new IllegalArgumentException("재고에 없는 상품입니다. productId=" + pid);
        }

        Set<Long> locked = Set.copyOf(cycleCountRepository.findOpenProductIds());
        List<Long> conflicts = distinct.stream().filter(locked::contains).toList();
        if (!conflicts.isEmpty())
            throw new IllegalStateException("이미 진행 중인 실사에 포함된 상품입니다. productId=" + conflicts);

        CycleCount session = CycleCount.open(actorProvider.current(), memo);
        for (Long pid : distinct)
            session.addItem(pid, inventories.get(pid).getOnHandQty());
        return cycleCountRepository.save(session);
    }

    @Transactional
    public void saveCounts(Long sessionId, Map<Long, Integer> countsByItemId) {
        CycleCount session = findById(sessionId);
        countsByItemId.forEach(session::recordCount);
    }

    @Transactional
    public void submit(Long sessionId) {
        findById(sessionId).submit(actorProvider.current());
    }

    public CycleCount findById(Long sessionId) {
        return cycleCountRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("실사가 없습니다. id=" + sessionId));
    }

    public List<CycleCount> findAll(CycleCountStatus status) {
        return status == null
                ? cycleCountRepository.findAllByOrderByIdDesc()
                : cycleCountRepository.findByStatusOrderByIdDesc(status);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountServiceTest*'`
Expected: PASS (7건)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/repository/CycleCountRepository.java \
        src/main/java/com/jhg/wms/service/CycleCountService.java \
        src/test/java/com/jhg/wms/service/CycleCountServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 실사 세션 생성·입력·제출

세션을 열 때 대상 상품의 장부 수량을 스냅샷으로 담는다(표시 전용).

같은 상품이 아직 종결되지 않은 세션에 있으면 새 세션 생성을 거부한다.
승인 시점 재계산이라 두 세션도 각자 수렴하긴 하지만, 나중 세션의 실물 수량은
이미 낡은 값이라 "센 시점"과 "적용 시점"이 어긋난 조정이 남는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 승인·반려 — `COUNT` 원장 반영

**Files:**
- Modify: `src/main/java/com/jhg/wms/domain/InventoryTransactionType.java`
- Modify: `src/main/java/com/jhg/wms/service/CycleCountService.java`
- Modify: `docs/wms-enum-schema-migration.md`
- Modify: `docs/wms-enum-schema-migration.sql`
- Test: `src/test/java/com/jhg/wms/service/CycleCountServiceTest.java`

**Interfaces:**
- Consumes: `InventoryService.applyDelta(Long, int, InventoryTransactionType, String, String): int` (기존)
- Produces: `CycleCountService.approve(Long sessionId): void`
- Produces: `CycleCountService.reject(Long sessionId, String reason): void`
- Produces: 원장 `reference` 형식 `"COUNT#{sessionId}"`
- Produces: `CycleCountService.appliedDeltas(Long sessionId): Map<Long, Integer>` — productId → 반영된 delta. 결과에 없으면 일치
- Produces: `InventoryTransactionRepository.findByReference(String): List<InventoryTransaction>`
- Produces: `new CycleCountService(CycleCountRepository, InventoryRepository, InventoryTransactionRepository, InventoryService, ActorProvider)` — **Task 4의 4인자에서 5인자로 변경**

- [ ] **Step 1: 실패 테스트를 쓴다**

Modify `src/test/java/com/jhg/wms/service/CycleCountServiceTest.java` — 파일 끝의 `itemId` 헬퍼 앞에 추가한다:

```java
    /** 실사는 세는 동안 재고가 움직인다. 차이는 승인 시점 장부로 다시 계산해야 원장 불변식이 유지된다. */
    @Test
    void 승인은_실사중_이동을_반영해_승인시점_장부로_차이를_계산한다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));   // 실물 14
        service.submit(c.getId());
        flush();
        inventoryService.applyDelta(1L, -2, com.jhg.wms.domain.InventoryTransactionType.SHIP,
                "ORDER#99", null);                                   // 실사 중 출고 → 장부 13
        flush();

        service.approve(c.getId());
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(14);
        assertThat(txnRepo.findAll())
                .filteredOn(t -> t.getType() == com.jhg.wms.domain.InventoryTransactionType.COUNT)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getDelta()).isEqualTo(1);       // 14 − 13
                    assertThat(t.getBeforeQty()).isEqualTo(13);
                    assertThat(t.getAfterQty()).isEqualTo(14);
                    assertThat(t.getReference()).isEqualTo("COUNT#" + c.getId());
                    assertThat(t.getActor()).isEqualTo("manager");
                });
    }

    @Test
    void 차이가_없는_품목은_원장에_남기지_않는다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 15));
        service.submit(c.getId());
        flush();

        service.approve(c.getId());
        flush();

        assertThat(txnRepo.findAll())
                .filteredOn(t -> t.getType() == com.jhg.wms.domain.InventoryTransactionType.COUNT)
                .isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.APPROVED);
    }

    @Test
    void 반려하면_장부와_원장이_그대로다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 3));
        service.submit(c.getId());
        flush();

        service.reject(c.getId(), "계수 오류");
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
        assertThat(txnRepo.findAll()).isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.REJECTED);
    }

    // 앞 품목만 반영되면 "절반만 승인된 실사"라는 설명할 수 없는 상태가 남는다.
    @Test
    void 한_품목이라도_실패하면_세션_전체가_롤백된다() {
        seed(1L, 15);
        seed(2L, 10);
        inventoryService.reserveAll(77L, Map.of(2L, 8));   // 상품2는 8개가 예약된 상태
        flush();
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 16, itemId(c, 2L), 3));  // 상품2는 예약 8 미만
        service.submit(c.getId());
        flush();

        assertThatThrownBy(() -> service.approve(c.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
        assertThat(txnRepo.findAll()).isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
    }

    @Test
    void 승인대기가_아니면_승인할_수_없다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();

        assertThatThrownBy(() -> service.approve(c.getId()))
                .isInstanceOf(IllegalStateException.class);
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountServiceTest*'`
Expected: 컴파일 실패 — `COUNT` 상수 없음, `approve/reject` 메서드 없음

- [ ] **Step 3: `COUNT` 원장 유형을 추가한다**

Modify `src/main/java/com/jhg/wms/domain/InventoryTransactionType.java`:

```java
package com.jhg.wms.domain;

public enum InventoryTransactionType {
    OPENING, // 초기 재고(시드·기존분 소급)
    RECEIVE, // 발주 입고
    SHIP,    // 출고
    ADJUST,  // 수동 조정
    RETURN,  // 반품 재입고
    COUNT    // 실사 차이 반영
}
```

- [ ] **Step 4: 승인·반려를 구현한다**

Modify `src/main/java/com/jhg/wms/service/CycleCountService.java` — import에 `com.jhg.wms.domain.CycleCountItem`, `com.jhg.wms.domain.InventoryTransactionType`을 추가하고, `submit` 아래에 넣는다:

```java
    /**
     * 승인. 차이 = 실물 − <b>승인 시점</b> 장부. 세는 동안 재고가 움직여도 원장 불변식이 유지된다.
     * 차이가 있는 품목만 applyDelta를 타므로, 재고가 늘면 OMS 백오더 승격 통지도 그대로 따라온다.
     * 한 품목이라도 실패하면 트랜잭션 전체가 롤백된다 — 절반만 반영된 실사를 만들지 않는다.
     */
    @Transactional
    public void approve(Long sessionId) {
        CycleCount session = findById(sessionId);
        if (session.getStatus() != CycleCountStatus.SUBMITTED)
            throw new IllegalStateException("승인 대기 상태에서만 승인할 수 있습니다.");

        Map<Long, Inventory> current = inventoryRepository.findByProductIdIn(
                        session.getItems().stream().map(CycleCountItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));

        for (CycleCountItem item : session.getItems()) {
            Inventory inv = current.get(item.getProductId());
            if (inv == null)
                throw new IllegalArgumentException("재고에 없는 상품입니다. productId=" + item.getProductId());
            int diff = item.getCountedQty() - inv.getOnHandQty();
            if (diff == 0) continue;   // 센 사실은 세션이 기록한다 — delta 0 원장 행은 노이즈다
            inventoryService.applyDelta(item.getProductId(), diff, InventoryTransactionType.COUNT,
                    "COUNT#" + sessionId, session.getMemo());
        }
        session.approve(actorProvider.current());
    }

    @Transactional
    public void reject(Long sessionId, String reason) {
        findById(sessionId).reject(actorProvider.current(), reason);
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountServiceTest*'`
Expected: PASS (12건)

- [ ] **Step 6: 승인 결과 조회를 추가한다**

품목별 "일치 / +1 / −2"를 화면에 보여주려면 반영된 차이가 필요하다. 품목에 저장하지 않고
원장에서 읽는다 — 같은 숫자의 정본을 둘로 만들지 않기 위해서다.

Modify `src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java` — 인터페이스 안에 추가:

```java
    List<InventoryTransaction> findByReference(String reference);
```

Modify `src/main/java/com/jhg/wms/service/CycleCountService.java` — `countPendingApproval` 위치와 무관하게 `findAll` 아래에 추가하고, 생성자 필드에 `private final InventoryTransactionRepository transactionRepository;`를 `inventoryService` 앞에 넣는다:

```java
    /** 이 세션이 실제로 반영한 품목별 차이. 원장이 정본이므로 세션에 복사해두지 않고 여기서 읽는다.
     *  결과에 없는 품목은 차이가 0이었다는 뜻이다(= 일치). */
    public Map<Long, Integer> appliedDeltas(Long sessionId) {
        return transactionRepository.findByReference("COUNT#" + sessionId).stream()
                .collect(Collectors.toMap(InventoryTransaction::getProductId,
                        InventoryTransaction::getDelta, Integer::sum));
    }
```

import 추가: `import com.jhg.wms.domain.InventoryTransaction;`, `import com.jhg.wms.repository.InventoryTransactionRepository;`

**주의**: 생성자 인자 순서가 바뀌므로 `CycleCountServiceTest.setUp()`의 생성자 호출도 함께 고친다:

```java
        service = new CycleCountService(cycleCountRepo, inventoryRepo, txnRepo, inventoryService, () -> "operator");
```

그리고 아래 테스트를 `CycleCountServiceTest`에 추가한다:

```java
    @Test
    void 반영된_차이는_원장에서_읽는다() {
        seed(1L, 15);
        seed(2L, 30);
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14, itemId(c, 2L), 30));
        service.submit(c.getId());
        flush();
        service.approve(c.getId());
        flush();

        assertThat(service.appliedDeltas(c.getId())).containsExactly(entry(1L, -1));  // 상품2는 일치라 없음
    }
```

import 추가: `import static org.assertj.core.api.Assertions.entry;`

- [ ] **Step 7: 재고 이력 화면에 실사 필터·라벨을 추가한다**

Modify `src/main/resources/templates/admin/inventory-transactions.html`:

1. 필터 줄(`type='RETURN'` 링크 다음)에 추가:

```html
    | <a th:href="@{/admin/inventory/transactions(type='COUNT')}">실사</a>
```

2. 유형 라벨 `th:switch` 블록의 `RETURN` case 아래에 추가:

```html
          <span th:case="'COUNT'">실사</span>
```

- [ ] **Step 8: prod 마이그레이션 문서를 갱신한다**

Modify `docs/wms-enum-schema-migration.sql` — `inventory_adjustment.type` check 제약을 재생성하는 부분의 값 목록에 `'COUNT'`를 추가한다(기존 `'RETURN'` 옆).

Modify `docs/wms-enum-schema-migration.md` — "다음에 enum 값을 추가할 때" 절 아래에 추가한다:

```markdown
## 실제 적용 이력

| 날짜 | 추가한 값 | 대상 컬럼 |
|------|-----------|-----------|
| 2026-08-12 | `RETURN` | `inventory_adjustment.type` |
| 2026-08-15 | `COUNT` | `inventory_adjustment.type` |

`COUNT`는 이 문서가 예고한 절차를 처음으로 그대로 따른 사례다 — 상수 추가 → 스크립트 갱신 → 배포.
prod 반영은 재배포 시점에 한다(Railway 중단 상태).
```

- [ ] **Step 9: 전체 테스트를 돌린다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/InventoryTransactionType.java \
        src/main/java/com/jhg/wms/service/CycleCountService.java \
        src/main/java/com/jhg/wms/repository/InventoryTransactionRepository.java \
        src/main/resources/templates/admin/inventory-transactions.html \
        src/test/java/com/jhg/wms/service/CycleCountServiceTest.java \
        docs/wms-enum-schema-migration.md docs/wms-enum-schema-migration.sql
git commit -m "$(cat <<'EOF'
feat(wms): 실사 승인·반려 — 차이를 COUNT 원장으로 반영

차이는 실물 − 승인 시점 장부로 계산한다. 세션 시작 시점 스냅샷으로 계산하면
실사 중 이동분이 이중 반영된다.

기존 applyDelta를 재사용한다. 재고 증가 경로가 하나뿐이라 실사로 재고가 늘면
OMS 백오더 승격 통지가 자동으로 따라온다 — 창고에 물건이 더 있었다는 사실은 입고와 같다.

차이가 0인 품목은 원장에 남기지 않는다. 센 사실은 세션이 기록하므로 delta 0 행은 노이즈다.
한 품목이라도 실패하면 세션 전체를 롤백한다 — 예약 수량 미만으로 줄이는 조정이 거부될 때
앞 품목만 반영되면 절반만 승인된 실사가 남는다.

COUNT 추가로 prod check 제약 갱신이 필요하다. 마이그레이션 문서가 예고한 절차의 첫 적용.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 관리자 화면과 권한

**Files:**
- Create: `src/main/java/com/jhg/wms/web/CycleCountAdminController.java`
- Create: `src/main/java/com/jhg/wms/web/CountForm.java`
- Create: `src/main/resources/templates/admin/cycle-counts.html`
- Create: `src/main/resources/templates/admin/cycle-count-new.html`
- Create: `src/main/resources/templates/admin/cycle-count-detail.html`
- Modify: `src/main/java/com/jhg/wms/config/SecurityConfig.java`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/java/com/jhg/wms/web/AdminDataAccessAdvice.java`
- Test: `src/test/java/com/jhg/wms/web/CycleCountAdminControllerTest.java`

**Interfaces:**
- Consumes: `CycleCountService.open/saveCounts/submit/approve/reject/findById/findAll/appliedDeltas` (Task 4·5), `InventoryService.findAllRows()` (기존)
- Produces: URL — `GET /admin/cycle-counts`, `GET /admin/cycle-counts/new`, `POST /admin/cycle-counts`, `GET /admin/cycle-counts/{id}`, `POST /admin/cycle-counts/{id}/counts`, `POST /admin/cycle-counts/{id}/submit`, `POST /admin/cycle-counts/{id}/approve`, `POST /admin/cycle-counts/{id}/reject`

- [ ] **Step 1: 실패 테스트를 쓴다**

Create `src/test/java/com/jhg/wms/web/CycleCountAdminControllerTest.java`:

```java
package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CycleCountAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class CycleCountAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CycleCountService cycleCountService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;

    private CycleCount submitted() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        ReflectionTestUtils.setField(c, "id", 7L);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        c.recordCount(101L, 14);
        c.submit("operator");
        return c;
    }

    @Test
    void 상세는_상태를_한글로_보여준다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("승인 대기")))
                .andExpect(content().string(not(containsString("SUBMITTED"))));
    }

    // 서버 인가로 막히지만, 눌러야 403을 알게 되는 버튼은 그 자체로 결함이다.
    @Test
    void OPERATOR에게는_승인_반려_버튼이_보이지_않는다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(content().string(not(containsString("ccApprove"))))
                .andExpect(content().string(not(containsString("ccReject"))));
    }

    @Test
    void OPERATOR가_승인을_직접_POST하면_403() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());

        verify(cycleCountService, never()).approve(anyLong());
    }

    @Test
    void MANAGER는_승인할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("mgr").roles("MANAGER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).approve(7L);
    }

    @Test
    void 승인된_세션은_품목별_반영_결과를_보여준다() throws Exception {
        CycleCount approved = submitted();
        approved.approve("manager");
        when(cycleCountService.findById(7L)).thenReturn(approved);
        when(cycleCountService.appliedDeltas(7L)).thenReturn(java.util.Map.of(1L, -1));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 14, 0, 14)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반영 결과")))
                .andExpect(content().string(containsString("-1")));
    }

    @Test
    void 겹치는_세션_생성은_에러메시지로_돌아간다() throws Exception {
        when(cycleCountService.open(any(), any()))
                .thenThrow(new IllegalStateException("이미 진행 중인 실사에 포함된 상품입니다. productId=[1]"));

        mockMvc.perform(post("/admin/cycle-counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("productIds", "1").param("memo", "겹치는 실사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountAdminControllerTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class CycleCountAdminController`

- [ ] **Step 3: 폼 바인딩 객체를 만든다**

Create `src/main/java/com/jhg/wms/web/CountForm.java`:

```java
package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 실사 실물 수량 입력 폼. countedQty는 Integer — 미입력(null)과 0을 구분해야 한다. */
@Getter @Setter
public class CountForm {
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    public static class Item {
        private Long itemId;
        private Integer countedQty;
    }
}
```

- [ ] **Step 4: 컨트롤러를 만든다**

Create `src/main/java/com/jhg/wms/web/CycleCountAdminController.java`:

```java
package com.jhg.wms.web;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class CycleCountAdminController {

    private final CycleCountService cycleCountService;
    private final InventoryService inventoryService;

    @GetMapping("/admin/cycle-counts")
    public String list(@RequestParam(required = false) CycleCountStatus status, Model model) {
        model.addAttribute("sessions", cycleCountService.findAll(status));
        model.addAttribute("activeStatus", status);
        return "admin/cycle-counts";
    }

    @GetMapping("/admin/cycle-counts/new")
    public String newForm(Model model) {
        model.addAttribute("rows", inventoryService.findAllRows());
        return "admin/cycle-count-new";
    }

    @PostMapping("/admin/cycle-counts")
    public String create(@RequestParam(required = false) List<Long> productIds,
                         @RequestParam(required = false) String memo,
                         RedirectAttributes ra) {
        try {
            CycleCount session = cycleCountService.open(productIds, memo);
            ra.addFlashAttribute("successMessage", "실사를 시작했습니다. (실사 #" + session.getId() + ")");
            return "redirect:/admin/cycle-counts/" + session.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/cycle-counts/new";
        }
    }

    @GetMapping("/admin/cycle-counts/{id}")
    public String detail(@PathVariable Long id, Model model) {
        CycleCount session = cycleCountService.findById(id);
        model.addAttribute("session", session);
        model.addAttribute("productNames", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::productName)));
        model.addAttribute("bookQtyNow", inventoryService.findAllRows().stream()
                .collect(Collectors.toMap(InventoryRowResponse::productId, InventoryRowResponse::onHandQty)));
        // 반영된 차이는 원장에서 읽는다 — 세션에 복사해두지 않는다
        model.addAttribute("appliedDeltas", cycleCountService.appliedDeltas(id));
        return "admin/cycle-count-detail";
    }

    @PostMapping("/admin/cycle-counts/{id}/counts")
    public String saveCounts(@PathVariable Long id, @ModelAttribute CountForm form, RedirectAttributes ra) {
        try {
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (var item : form.getItems())
                if (item.getCountedQty() != null)   // 미입력은 건너뛴다 — 부분 저장을 허용한다
                    counts.put(item.getItemId(), item.getCountedQty());
            cycleCountService.saveCounts(id, counts);
            ra.addFlashAttribute("successMessage", "실물 수량을 저장했습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/submit")
    public String submit(@PathVariable Long id, RedirectAttributes ra) {
        try {
            cycleCountService.submit(id);
            ra.addFlashAttribute("successMessage", "실사를 제출했습니다. 승인 대기 상태입니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes ra) {
        try {
            cycleCountService.approve(id);
            ra.addFlashAttribute("successMessage", "실사를 승인했습니다. 차이가 재고에 반영됐습니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }

    @PostMapping("/admin/cycle-counts/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        try {
            cycleCountService.reject(id, reason);
            ra.addFlashAttribute("successMessage", "실사를 반려했습니다. 재고는 그대로입니다.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/cycle-counts/" + id;
    }
}
```

- [ ] **Step 5: 인가 규칙과 예외 처리 대상에 추가한다**

Modify `src/main/java/com/jhg/wms/config/SecurityConfig.java` — `/admin/returns/*/cancel` 줄 아래에 추가:

```java
                .requestMatchers(HttpMethod.POST, "/admin/cycle-counts/*/approve").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/cycle-counts/*/reject").hasRole("MANAGER")
```

Modify `src/main/java/com/jhg/wms/web/AdminDataAccessAdvice.java` — `@ControllerAdvice` 대상에 추가:

```java
@ControllerAdvice(assignableTypes = {WmsAdminController.class, RmaAdminController.class,
                                     CycleCountAdminController.class})
```

- [ ] **Step 6: 템플릿 3개를 만든다**

Create `src/main/resources/templates/admin/cycle-counts.html`:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('WMS 실사')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('cycle-counts')}"></nav>
<main>
  <h2>재고 실사</h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <div class="tabs">
    <a th:href="@{/admin/cycle-counts}" th:classappend="${activeStatus == null} ? 'active'">전체</a>
    <a th:href="@{/admin/cycle-counts(status='OPEN')}" th:classappend="${activeStatus?.name() == 'OPEN'} ? 'active'">작성 중</a>
    <a th:href="@{/admin/cycle-counts(status='SUBMITTED')}" th:classappend="${activeStatus?.name() == 'SUBMITTED'} ? 'active'">승인 대기</a>
    <a th:href="@{/admin/cycle-counts(status='APPROVED')}" th:classappend="${activeStatus?.name() == 'APPROVED'} ? 'active'">승인</a>
    <a th:href="@{/admin/cycle-counts(status='REJECTED')}" th:classappend="${activeStatus?.name() == 'REJECTED'} ? 'active'">반려</a>
  </div>

  <p><a class="btn" th:href="@{/admin/cycle-counts/new}">실사 시작</a></p>

  <div class="table-wrap">
    <table>
      <thead>
        <tr><th>번호</th><th>상태</th><th>메모</th><th>대상</th><th>진행</th><th>계수자</th><th>승인자</th><th>시작</th></tr>
      </thead>
      <tbody>
        <tr th:each="s : ${sessions}" th:data-href="@{/admin/cycle-counts/{id}(id=${s.id})}">
          <td><a th:href="@{/admin/cycle-counts/{id}(id=${s.id})}" th:text="${s.id}">1</a></td>
          <td>
            <span class="badge" th:classappend="'badge-cc-' + ${#strings.toLowerCase(s.status.name())}"
                  th:switch="${s.status.name()}">
              <span th:case="'OPEN'">작성 중</span>
              <span th:case="'SUBMITTED'">승인 대기</span>
              <span th:case="'APPROVED'">승인</span>
              <span th:case="'REJECTED'">반려</span>
            </span>
          </td>
          <td th:text="${s.memo}">8월 순환 실사</td>
          <td th:text="${#lists.size(s.items)} + '개'">3개</td>
          <td th:text="${#lists.size(s.items.?[countedQty != null])} + ' / ' + ${#lists.size(s.items)}">1 / 3</td>
          <td th:text="${s.createdBy}">operator</td>
          <td th:text="${s.approvedBy != null ? s.approvedBy : (s.rejectedBy != null ? s.rejectedBy : '—')}">—</td>
          <td th:text="${#temporals.format(s.createdAt, 'yyyy-MM-dd HH:mm')}">—</td>
        </tr>
        <tr th:if="${#lists.isEmpty(sessions)}"><td colspan="8">실사 내역이 없습니다.</td></tr>
      </tbody>
    </table>
  </div>
</main>
</body>
</html>
```

Create `src/main/resources/templates/admin/cycle-count-new.html`:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 실사 시작')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('cycle-counts')}"></nav>
<main>
  <h2>실사 시작</h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <form th:action="@{/admin/cycle-counts}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
    <p>
      <label for="memo">메모</label>
      <input type="text" id="memo" name="memo" placeholder="예: 8월 순환 실사" style="width:320px" />
    </p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>선택</th><th>상품 ID</th><th>상품명</th><th>장부 수량</th></tr></thead>
        <tbody>
          <tr th:each="row : ${rows}">
            <td><input type="checkbox" name="productIds" th:value="${row.productId}" /></td>
            <td th:text="${row.productId}">1</td>
            <td th:text="${row.productName}">상품 1</td>
            <td th:text="${row.onHandQty}">15</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="detail-actions">
      <button type="submit">선택한 상품으로 실사 시작</button>
    </div>
  </form>

  <p><a th:href="@{/admin/cycle-counts}">← 실사 목록</a></p>
</main>
</body>
</html>
```

Create `src/main/resources/templates/admin/cycle-count-detail.html`:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('WMS 실사 상세')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('cycle-counts')}"></nav>
<main>
  <h2>실사 #<span th:text="${session.id}">1</span></h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <p>
    상태:
    <span class="badge" th:classappend="'badge-cc-' + ${#strings.toLowerCase(session.status.name())}"
          th:switch="${session.status.name()}">
      <span th:case="'OPEN'">작성 중</span>
      <span th:case="'SUBMITTED'">승인 대기</span>
      <span th:case="'APPROVED'">승인</span>
      <span th:case="'REJECTED'">반려</span>
    </span>
    · 메모: <span th:text="${session.memo}">—</span>
    · 계수자: <b th:text="${session.createdBy}">operator</b>
    <span th:if="${session.approvedBy != null}"> · 승인자: <b th:text="${session.approvedBy}">manager</b></span>
    <span th:if="${session.rejectedBy != null}"> · 반려자: <b th:text="${session.rejectedBy}">manager</b>
      (사유: <span th:text="${session.rejectReason}">—</span>)</span>
  </p>

  <!-- 작성 중: 실물 수량 입력 -->
  <form id="ccCounts" th:if="${session.status.name() == 'OPEN'}"
        th:action="@{/admin/cycle-counts/{id}/counts(id=${session.id})}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>상품</th><th>세션 시작 장부</th><th>현재 장부</th><th>실물 수량</th></tr>
        </thead>
        <tbody>
          <tr th:each="item, stat : ${session.items}">
            <td th:text="${productNames.getOrDefault(item.productId, '상품#' + item.productId)}">상품 1</td>
            <td th:text="${item.bookQtyAtOpen}">15</td>
            <td th:text="${bookQtyNow.getOrDefault(item.productId, 0)}">13</td>
            <td>
              <input type="hidden" th:name="|items[${stat.index}].itemId|" th:value="${item.id}" />
              <!-- 기본값을 두지 않는다 — 미입력과 0("세어보니 없었다")을 구분해야 한다. -->
              <input type="number" th:name="|items[${stat.index}].countedQty|" min="0"
                     th:value="${item.countedQty}" style="width:90px" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </form>

  <!-- 제출 이후: 읽기 전용 + 차이 -->
  <div class="table-wrap" th:if="${session.status.name() != 'OPEN'}">
    <table>
      <thead>
        <tr><th>상품</th><th>세션 시작 장부</th><th>실물 수량</th><th>현재 장부</th><th>반영 결과</th></tr>
      </thead>
      <tbody>
        <tr th:each="item : ${session.items}">
          <td th:text="${productNames.getOrDefault(item.productId, '상품#' + item.productId)}">상품 1</td>
          <td th:text="${item.bookQtyAtOpen}">15</td>
          <td th:text="${item.countedQty != null ? item.countedQty : '—'}">14</td>
          <td th:text="${bookQtyNow.getOrDefault(item.productId, 0)}">14</td>
          <!-- 원장에 행이 없으면 차이가 0이었다는 뜻 = 일치. 승인 전에는 아직 판정할 수 없다. -->
          <td th:if="${session.status.name() != 'APPROVED'}">—</td>
          <td th:if="${session.status.name() == 'APPROVED'}"
              th:text="${appliedDeltas.containsKey(item.productId)
                         ? (appliedDeltas.get(item.productId) > 0 ? '+' + appliedDeltas.get(item.productId)
                                                                 : appliedDeltas.get(item.productId))
                         : '일치'}">일치</td>
        </tr>
      </tbody>
    </table>
  </div>

  <form id="ccSubmit" th:if="${session.status.name() == 'OPEN'}"
        th:action="@{/admin/cycle-counts/{id}/submit(id=${session.id})}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
  </form>

  <form id="ccApprove" sec:authorize="hasRole('MANAGER')" th:if="${session.status.name() == 'SUBMITTED'}"
        th:action="@{/admin/cycle-counts/{id}/approve(id=${session.id})}" method="post"
        onsubmit="return confirm('승인하면 차이가 재고에 반영되고 되돌릴 수 없습니다. 진행하시겠습니까?');">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
  </form>

  <form id="ccReject" sec:authorize="hasRole('MANAGER')" th:if="${session.status.name() == 'SUBMITTED'}"
        th:action="@{/admin/cycle-counts/{id}/reject(id=${session.id})}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
    <input type="text" name="reason" placeholder="반려 사유" style="width:260px" />
  </form>

  <div class="detail-actions">
    <button type="submit" form="ccCounts" th:if="${session.status.name() == 'OPEN'}">실물 수량 저장</button>
    <button type="submit" form="ccSubmit" th:if="${session.status.name() == 'OPEN'}">제출</button>
    <button type="submit" form="ccApprove" sec:authorize="hasRole('MANAGER')"
            th:if="${session.status.name() == 'SUBMITTED'}">승인</button>
    <button type="submit" form="ccReject" class="btn btn--danger" sec:authorize="hasRole('MANAGER')"
            th:if="${session.status.name() == 'SUBMITTED'}">반려</button>
  </div>

  <p><a th:href="@{/admin/cycle-counts}">← 실사 목록</a></p>
</main>
</body>
</html>
```

- [ ] **Step 7: 내비게이션에 링크를 추가한다**

Modify `src/main/resources/templates/fragments/layout.html` — `반품` 링크 아래에 추가:

```html
    <a th:href="@{/admin/cycle-counts}" th:classappend="${active == 'cycle-counts'} ? 'active'">실사</a>
```

- [ ] **Step 8: 배지 스타일을 추가한다**

Modify `src/main/resources/static/css/admin.css` — 파일 끝에 추가:

```css

/* 실사 상태 */
.badge-cc-open      { background: #e5edff; color: #1e40af; }
.badge-cc-submitted { background: #fff4d6; color: #92600a; }
.badge-cc-approved  { background: #dcfce7; color: #166534; }
.badge-cc-rejected  { background: #f1f1f1; color: #6b7280; text-decoration: line-through; }
```

- [ ] **Step 9: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*CycleCountAdminControllerTest*'`
Expected: PASS (6건)

- [ ] **Step 10: 전체 테스트를 돌린다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
Expected: PASS

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/CycleCountAdminController.java \
        src/main/java/com/jhg/wms/web/CountForm.java \
        src/main/java/com/jhg/wms/config/SecurityConfig.java \
        src/main/java/com/jhg/wms/web/AdminDataAccessAdvice.java \
        src/main/resources/templates/admin/cycle-counts.html \
        src/main/resources/templates/admin/cycle-count-new.html \
        src/main/resources/templates/admin/cycle-count-detail.html \
        src/main/resources/templates/fragments/layout.html \
        src/main/resources/static/css/admin.css \
        src/test/java/com/jhg/wms/web/CycleCountAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 실사 관리자 화면 — 계수와 승인 분리

목록·생성·상세 화면과 인가 규칙. 승인·반려는 MANAGER 전용이고 화면에서도
sec:authorize로 감춘다 — 눌러야 403을 알게 되는 버튼은 그 자체로 결함이다.

실물 수량 입력칸에 기본값을 두지 않는다. 미입력과 0("세어보니 없었다")을 구분해야
안 센 품목이 조용히 확정되지 않는다.
승인은 되돌릴 수 없으므로 확인창을 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 대시보드 카드와 문서 현행화

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Modify: `README.md`
- Modify: `docs/wms-business-roadmap.md`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: `CycleCountRepository.countByStatus(CycleCountStatus)` (Task 4)

- [ ] **Step 1: 실패 테스트를 쓴다**

Modify `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` — 클래스 상단 `@MockitoBean` 목록에 추가:

```java
    @MockitoBean CycleCountService cycleCountService;
```

파일 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    void 대시보드는_승인대기_실사_건수를_보여준다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of());
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of());
        when(replenishmentRequestService.findAll()).thenReturn(List.of());
        when(inventoryService.findAllReservations()).thenReturn(List.of());
        when(rmaService.findAll(null)).thenReturn(List.of());
        when(cycleCountService.countPendingApproval()).thenReturn(2L);

        mockMvc.perform(get("/").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingCycleCountCount", 2L))
                .andExpect(content().string(containsString("실사 승인 대기")));
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'`
Expected: 컴파일 실패 — `countPendingApproval()` 없음

- [ ] **Step 3: 집계 메서드를 만든다**

Modify `src/main/java/com/jhg/wms/service/CycleCountService.java` — `findAll` 아래에 추가:

```java
    /** 대시보드 "처리 대기" 집계 — 세션을 전부 로드하지 않고 개수만 센다. */
    public long countPendingApproval() {
        return cycleCountRepository.countByStatus(CycleCountStatus.SUBMITTED);
    }
```

- [ ] **Step 4: 대시보드에 카드를 추가한다**

Modify `src/main/java/com/jhg/wms/web/WmsAdminController.java`:

1. import 추가: `import com.jhg.wms.service.CycleCountService;`
2. 필드 추가: `private final CycleCountService cycleCountService;`
3. `dashboard(...)`의 `return "admin/dashboard";` 바로 위에 추가:

```java
        model.addAttribute("pendingCycleCountCount", cycleCountService.countPendingApproval());
```

Modify `src/main/resources/templates/admin/dashboard.html` — 반품 카드 `</div>` 아래에 추가:

```html
    <div class="card" th:if="${pendingCycleCountCount != null}">
      <h3>실사 승인 대기</h3>
      <p class="big" th:text="${pendingCycleCountCount}">0</p>
      <p class="label">계수 완료, 승인 필요</p>
      <p><a th:href="@{/admin/cycle-counts(status='SUBMITTED')}">승인 대기 →</a></p>
    </div>
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*WmsAdminControllerTest*'`
Expected: PASS

- [ ] **Step 6: 문서를 현행화한다**

Modify `README.md`:

1. 상단 요약 표의 재고 원장 줄을 교체:

```markdown
| 재고 원장 | `OPENING / RECEIVE / SHIP / ADJUST / RETURN / COUNT` — 불변식 Σdelta == onHand, 행위자 기록 |
```

2. 테스트 개수를 최종 값으로 갱신한다(Step 7에서 실제 숫자를 확인한 뒤 적는다).

3. 관리자 UI 표의 반품 줄 아래에 추가:

```markdown
| `/admin/cycle-counts` | 재고 실사 — 세션 목록·진행률, 상태 필터 | 인증 |
| `/admin/cycle-counts/new` | 대상 상품 선택 후 실사 시작 | 인증 |
| `/admin/cycle-counts/{id}` | 실사 상세 — 실물 수량 입력·제출 | 인증 |
| `/admin/cycle-counts/{id}/approve`·`/reject` (POST) | 실사 승인 · 반려 | **MANAGER** |
```

4. "재고 상태 흐름" 절의 원장 유형 목록에 `COUNT`를 추가하고, 아래 문단을 그 절 끝에 추가:

```markdown
### 재고 실사

장부와 실물이 어긋났을 때 **누가 무엇을 근거로 맞췄는지**에 답하기 위한 절차입니다.
계수(OPERATOR)와 승인(MANAGER)을 분리해, 센 사람이 스스로 장부를 고치지 못하게 합니다.

```
OPEN ──(실물 입력, 전 품목 필수)──▶ SUBMITTED ──(승인)──▶ APPROVED  차이만 COUNT 원장
                                              └─(반려)──▶ REJECTED  장부 불변
```

- **차이 = 실물 − 승인 시점 장부.** 실사는 세는 동안 시간이 흐르고 그 사이 입출고가 계속됩니다.
  승인 시점으로 재계산하면 원장 불변식이 저절로 유지되고, 실사 때문에 주문 출고를 막을 필요도 없습니다.
  대신 세는 동안 움직인 수량은 차이에 섞입니다 — 이를 구분하려면 대상 동결이 필요한데,
  재고 정본을 한 곳에 둔 구조에서 동결은 OMS 주문까지 막으므로 택하지 않았습니다.
- 차이가 있는 품목만 기존 `applyDelta`를 타므로, 실사로 재고가 늘면 **OMS 백오더 승격 통지가 자동으로 따라옵니다.**
- 한 품목이라도 실패하면 세션 전체가 롤백됩니다 — 절반만 승인된 실사를 만들지 않습니다.
- 같은 상품이 진행 중인 다른 세션에 있으면 새 세션 생성을 거부합니다.

모든 원장 행에는 **행위자**가 남습니다 — 사람은 사용자명, OMS 서버간 호출은 서비스 계정명,
기동 시드는 `system`. 이 필드가 생기기 전 행은 알 수 없는 정보라 백필하지 않고 `—`로 표시합니다.
```

Modify `docs/wms-business-roadmap.md` — "1. 재고 실사" 절을 교체:

```markdown
### ~~1. 재고 실사~~ ✅ 완료 (2026-08-15)

V2.1로 구현 완료. 설계: `docs/superpowers/specs/v2/2026-08-15-cycle-count-actor-design.md`.
계수·승인 분리, 승인 시점 재계산, `COUNT` 원장. 원장 행위자 기록도 함께 들어갔다.
```

같은 문서의 완료 기능 표에 행을 추가:

```markdown
| 재고 실사 | 세션 기반 계수·승인 분리, 승인 시점 차이 재계산, `COUNT` 원장, 원장 행위자 |
```

- [ ] **Step 7: 전체 테스트를 돌리고 실제 개수를 README에 반영한다**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew cleanTest test`
Expected: PASS

개수 확인:

```bash
python3 -c "
import glob,re
t=f=0
for p in glob.glob('build/test-results/test/*.xml'):
    m=re.search(r'tests=\"(\d+)\".*?failures=\"(\d+)\".*?errors=\"(\d+)\"',open(p,encoding='utf-8').read(400))
    if m: t+=int(m.group(1)); f+=int(m.group(2))+int(m.group(3))
print(t,'건, 실패',f)"
```

출력된 숫자로 `README.md`의 `| 테스트 | 206개 ... |` 줄을 갱신한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/java/com/jhg/wms/service/CycleCountService.java \
        src/main/resources/templates/admin/dashboard.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java \
        README.md docs/wms-business-roadmap.md
git commit -m "$(cat <<'EOF'
feat(wms): 대시보드 실사 승인 대기 카드 + 문서 현행화

승인 대기는 사람이 손대야 진행되는 일이라 처리 대기 대시보드에 올린다.
세션을 전부 로드하지 않고 countByStatus로 개수만 센다.

README에 실사 절과 행위자 기록을 추가하고, 로드맵의 "1. 재고 실사"를 완료 처리한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## 완료 기준

- `./gradlew cleanTest test` 전부 통과(시작 206건 + 신규 약 40건)
- `./gradlew build` 성공(bootJar 포함)
- 로컬 수동 확인: operator로 실사 생성·입력·제출 → manager로 승인 → 재고와 `COUNT` 원장 반영,
  이력 화면에 행위자 표시, operator에게 승인 버튼 미노출
- prod Postgres 마이그레이션 스크립트에 `COUNT` 반영(적용은 재배포 시점)
