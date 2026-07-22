# 재고 트랜잭션 원장 (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실물(onHand)을 바꾸는 모든 경로가 재고 트랜잭션 원장에 한 줄씩 남겨, 원장만으로 현재 수량을 0부터 재구성할 수 있게 한다.

**Architecture:** 기존 `InventoryAdjustment` 엔티티를 `InventoryTransaction`으로 승격(클래스만 리네임, **테이블명은 `inventory_adjustment` 유지** → prod 크로스테이블 마이그레이션 회피). `type`·`reference` 두 필드를 추가하고, onHand를 바꾸는 지점(수동조정·발주입고·출고·시드)을 원장 기록과 함께 통과시킨다. 기존 행·재고는 기동 시 멱등 백필.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Data JPA, JUnit5 + AssertJ + Mockito, `@DataJpaTest`.

## Global Constraints

- Java 21 / Spring Boot 3.5.5. 기존 코드 스타일·한글 주석 관례 유지.
- **Flyway 미도입** — 스키마 외 데이터 마이그레이션은 애플리케이션 **기동 루틴(멱등)**으로 한다(`InitDb` 패턴).
- prod `ddl-auto: update`(컬럼 추가만), 로컬/테스트 H2 재생성. 새 컬럼은 **nullable**로 추가(기존 행 수용).
- 원장은 insert-only(불변). 예약/해제는 원장에 남기지 않는다(Reservation 원장 담당).
- TDD: 실패 테스트 → 최소 구현 → 통과 → 커밋. 테스트: `./gradlew test`.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

## 파일 구조

- `domain/InventoryTransaction.java` — 엔티티(구 `InventoryAdjustment` 리네임), `@Table(name="inventory_adjustment")`.
- `domain/InventoryTransactionType.java` — enum `{ OPENING, RECEIVE, SHIP, ADJUST }` (신규).
- `repository/InventoryTransactionRepository.java` — 리포지토리(구 `InventoryAdjustmentRepository` 리네임) + 백필 쿼리.
- `service/InventoryService.java` — `applyDelta` 코어 + 경로별 기록, `findAllTransactions`.
- `service/PurchaseOrderService.java` — 입고 시 RECEIVE 기록.
- `InitDb.java`(`InitService`) — 시드 OPENING + 기존 데이터 백필.
- `web/WmsAdminController.java` + `templates/admin/inventory.html` — 트랜잭션 이력 화면 + type 필터.
- 테스트: `service/InventoryServiceTest.java`, `service/PurchaseOrderServiceTest.java`, 신규 재구성 불변식 테스트.

---

### Task 1: 엔티티·enum·리포지토리 승격 (동작 불변)

클래스 리네임 + 필드 2개 추가. 테이블명은 유지. 기존 "수동조정 기록" 동작은 그대로(type=ADJUST로).

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/InventoryTransactionType.java`
- Rename: `domain/InventoryAdjustment.java` → `domain/InventoryTransaction.java`
- Rename: `repository/InventoryAdjustmentRepository.java` → `repository/InventoryTransactionRepository.java`
- Modify: `service/InventoryService.java`, `web/WmsAdminController.java`
- Test: `service/InventoryServiceTest.java`, `service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Produces:
  - `enum InventoryTransactionType { OPENING, RECEIVE, SHIP, ADJUST }`
  - `InventoryTransaction.of(Long productId, InventoryTransactionType type, int delta, int beforeQty, int afterQty, String reference, String reason)` → `InventoryTransaction`
  - `InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long>` with `List<InventoryTransaction> findAllByOrderByIdDesc()`
  - `InventoryService(InventoryRepository, ReservationRepository, InventoryTransactionRepository, OmsReplenishmentNotifier)` (필드 리네임으로 생성자 시그니처 변경)

- [ ] **Step 1: enum 생성**

`domain/InventoryTransactionType.java`:
```java
package com.jhg.wms.domain;

public enum InventoryTransactionType {
    OPENING, // 초기 재고(시드·기존분 소급)
    RECEIVE, // 발주 입고
    SHIP,    // 출고
    ADJUST   // 수동 조정
}
```

- [ ] **Step 2: 엔티티 리네임 + 필드 추가**

`domain/InventoryAdjustment.java`를 `domain/InventoryTransaction.java`로 옮기고 내용 교체:
```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 재고 트랜잭션 원장(insert-only). onHand를 바꾸는 모든 경로가 한 줄씩 남긴다.
 *  ponytail: 테이블명은 inventory_adjustment 유지 — prod 크로스테이블 마이그레이션 회피(클래스만 승격). */
@Entity
@Table(name = "inventory_adjustment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryTransaction {

    @Id @GeneratedValue
    @Column(name = "inventory_adjustment_id")
    private Long id;

    @Column(nullable = false)
    private Long productId;

    // nullable: 기존 행(구 조정) 수용 — 기동 백필이 ADJUST로 채운다.
    @Enumerated(EnumType.STRING)
    private InventoryTransactionType type;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false)
    private int beforeQty;

    @Column(nullable = false)
    private int afterQty;

    // 추적용: "PO#12", "ORDER#34". OPENING·수동조정은 null.
    private String reference;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static InventoryTransaction of(Long productId, InventoryTransactionType type, int delta,
                                          int beforeQty, int afterQty, String reference, String reason) {
        InventoryTransaction t = new InventoryTransaction();
        t.productId = productId;
        t.type = type;
        t.delta = delta;
        t.beforeQty = beforeQty;
        t.afterQty = afterQty;
        t.reference = reference;
        t.reason = reason;
        t.createdAt = LocalDateTime.now();
        return t;
    }
}
```
그리고 구 `InventoryAdjustment.java` 파일 삭제.

- [ ] **Step 3: 리포지토리 리네임**

`repository/InventoryAdjustmentRepository.java` 삭제, `repository/InventoryTransactionRepository.java` 생성:
```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.InventoryTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    List<InventoryTransaction> findAllByOrderByIdDesc();
}
```

- [ ] **Step 4: InventoryService 참조 갱신 (동작 유지)**

`service/InventoryService.java`에서 import·필드·타입을 교체하고, 기존 3-arg `adjust`가 트랜잭션을 type=ADJUST로 남기게 한다(2-arg는 Task 2에서 흡수하니 지금은 그대로 둠):
- import `InventoryAdjustment`/`InventoryAdjustmentRepository` → `InventoryTransaction`/`InventoryTransactionRepository`
- 필드 `adjustmentRepository` (타입 `InventoryTransactionRepository`)
- 3-arg `adjust` 저장부:
```java
transactionRepository.save(
    InventoryTransaction.of(productId, InventoryTransactionType.ADJUST, delta, before, after, null, reason));
```
- `findAllAdjustments()`의 반환 타입만 `List<InventoryTransaction>`로(메서드명은 Task 7에서 리네임). 필드명은 `transactionRepository`로 통일.

`web/WmsAdminController.java`: `findAllAdjustments()` 호출은 그대로(반환 타입만 변경됨). import 조정 없으면 무변경.

- [ ] **Step 5: 테스트 참조 갱신**

`service/InventoryServiceTest.java`·`service/PurchaseOrderServiceTest.java`에서:
- `InventoryAdjustmentRepository adjustmentRepo` → `InventoryTransactionRepository adjustmentRepo`
- `new InventoryService(repo, reservationRepo, adjustmentRepo, notifier)` 인자 타입만 바뀜(코드 동일)
- `InventoryAdjustment` 참조가 있으면 `InventoryTransaction`으로.

- [ ] **Step 6: 컴파일·전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (기존 테스트 전부 green — 동작 불변).

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(wms): InventoryAdjustment→InventoryTransaction 승격 (type·reference 추가, 동작 불변)"
```

---

### Task 2: `applyDelta` 코어 + 수동조정 통합

onHand 변경 + 원장 기록을 한 지점으로 모으고, 수동조정을 그리로 통과시킨다.

**Files:**
- Modify: `service/InventoryService.java`
- Test: `service/InventoryServiceTest.java`

**Interfaces:**
- Consumes: `InventoryTransaction.of(...)`, `InventoryTransactionType`
- Produces:
  - `int applyDelta(Long productId, int delta, InventoryTransactionType type, String reference, String reason)` — onHand 적용·가드·원장 기록·OMS 통지(delta>0)
  - `int adjust(Long productId, int delta, String reason)` (유지, 내부는 applyDelta 위임)

- [ ] **Step 1: 실패 테스트 작성**

`service/InventoryServiceTest.java`에 추가:
```java
@Test
void adjust_수동조정하면_ADJUST_트랜잭션이_남는다() {
    seed(1L, 10);
    service.adjust(1L, -3, "파손");
    var txns = adjustmentRepo.findAllByOrderByIdDesc();
    assertThat(txns).hasSize(1);
    assertThat(txns.get(0).getType()).isEqualTo(com.jhg.wms.domain.InventoryTransactionType.ADJUST);
    assertThat(txns.get(0).getDelta()).isEqualTo(-3);
    assertThat(txns.get(0).getBeforeQty()).isEqualTo(10);
    assertThat(txns.get(0).getAfterQty()).isEqualTo(7);
    assertThat(txns.get(0).getReason()).isEqualTo("파손");
    assertThat(txns.get(0).getReference()).isNull();
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: FAIL (getType()/기록 없음 또는 어서션 불일치).
> Task 1 저장부가 이미 ADJUST를 남기면 이 테스트는 바로 통과할 수도 있다. 그 경우 Step 3에서 리팩터만 하고 green 유지.

- [ ] **Step 3: `applyDelta` 도입 + 기존 adjust 위임**

`service/InventoryService.java`에서 기존 2-arg/3-arg `adjust`를 다음으로 교체:
```java
/** onHand 변경 + 원장 기록의 유일 지점. 모든 실물 변동 경로가 통과한다. */
@Transactional
public int applyDelta(Long productId, int delta, InventoryTransactionType type,
                      String reference, String reason) {
    Inventory inv = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId));
    int before = inv.getOnHandQty();
    int after = before + delta;
    if (after < 0)
        throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + before + "개)");
    if (after < inv.getReservedQty())
        throw new IllegalArgumentException("예약된 수량(" + inv.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
    inv.setOnHandQty(after);
    transactionRepository.save(InventoryTransaction.of(productId, type, delta, before, after, reference, reason));
    if (delta > 0) {
        // 모든 재고 증가가 통과 — OMS 백오더 승격 트리거(트랜잭션 커밋 후).
        omsReplenishmentNotifier.notifyAfterCommit(productId);
    }
    return after;
}

/** 관리자 수동 재고 조정(+/-). */
@Transactional
public int adjust(Long productId, int delta, String reason) {
    return applyDelta(productId, delta, InventoryTransactionType.ADJUST, null, reason);
}
```
기존 2-arg `adjust(pid, delta)`는 제거(호출부 PurchaseOrderService는 Task 3에서 applyDelta로 전환).
> 이 시점에 `PurchaseOrderService`가 2-arg adjust를 호출하고 있으면 컴파일 실패한다. Task 3와 함께 진행하거나, 임시로 2-arg adjust를 남겨도 됨. **권장: Task 2·3를 한 커밋 흐름으로 이어 진행.**

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(wms): applyDelta 코어 — onHand 변경·원장 기록 단일 지점화"
```

---

### Task 3: 발주 입고 → RECEIVE 기록

**Files:**
- Modify: `service/PurchaseOrderService.java`
- Test: `service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Consumes: `applyDelta(Long, int, InventoryTransactionType, String, String)`

- [ ] **Step 1: 실패 테스트 작성**

`service/PurchaseOrderServiceTest.java`에 추가(기존 seed/발주 헬퍼 재사용; 없으면 재고·발주 생성 후):
```java
@Test
void receive_입고하면_RECEIVE_트랜잭션이_남는다() {
    // given: 재고 pid=1 onHand 5, 해당 상품 10개 발주
    // (기존 테스트의 재고·발주 생성 패턴을 그대로 사용)
    Long poId = /* 생성한 발주 id */;
    // when
    purchaseOrderService.receive(poId);
    // then
    var txns = transactionRepo.findAllByOrderByIdDesc();
    assertThat(txns.get(0).getType()).isEqualTo(InventoryTransactionType.RECEIVE);
    assertThat(txns.get(0).getReference()).isEqualTo("PO#" + poId);
    assertThat(txns.get(0).getDelta()).isEqualTo(10);
}
```
> `transactionRepo`(InventoryTransactionRepository)를 테스트에 주입. 발주 생성·재고 seed는 이 파일의 기존 테스트 방식을 따른다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests PurchaseOrderServiceTest`
Expected: FAIL (RECEIVE 미기록 — 현재 2-arg adjust 경로).

- [ ] **Step 3: 입고 경로를 applyDelta로 전환**

`service/PurchaseOrderService.java` `receive`:
```java
po.getItems().forEach(item ->
    inventoryService.applyDelta(item.getProductId(), item.getQuantity(),
        InventoryTransactionType.RECEIVE, "PO#" + poId, null));
```
import `com.jhg.wms.domain.InventoryTransactionType` 추가.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests PurchaseOrderServiceTest`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(wms): 발주 입고를 RECEIVE 트랜잭션으로 기록"
```

---

### Task 4: 출고 → SHIP 기록

`Inventory.ship()`은 onHand·reserved를 동시에 깎으므로 applyDelta를 쓰지 않고 전용 기록 루프를 둔다(onHand 델타 −qty만 SHIP으로 남김).

**Files:**
- Modify: `service/InventoryService.java`
- Test: `service/InventoryServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
@Test
void shipAll_출고하면_SHIP_트랜잭션이_상품당_남는다() {
    seed(1L, 10); seed(2L, 5);
    service.reserveAll(77L, Map.of(1L, 3, 2L, 2));
    service.shipAll(77L, Map.of(1L, 3, 2L, 2));
    var ships = adjustmentRepo.findAllByOrderByIdDesc().stream()
            .filter(t -> t.getType() == InventoryTransactionType.SHIP).toList();
    assertThat(ships).hasSize(2);
    assertThat(ships).allSatisfy(t -> assertThat(t.getReference()).isEqualTo("ORDER#77"));
    assertThat(ships.stream().mapToInt(t -> t.getDelta()).sum()).isEqualTo(-5); // -3 + -2
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: FAIL (SHIP 미기록).

- [ ] **Step 3: `shipAll` 전용 기록 루프**

`service/InventoryService.java` `shipAll`에서 `applyFromLedger(reservation.getQtyByProductId(), Inventory::ship);`를 다음으로 교체:
```java
Map<Long, Integer> ledger = reservation.getQtyByProductId();
Map<Long, Inventory> byId = inventoryRepository.findByProductIdIn(ledger.keySet())
        .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));
ledger.forEach((pid, qty) -> {
    Inventory inv = byId.get(pid);
    if (inv == null)
        throw new IllegalStateException("재고 행이 없어 처리할 수 없습니다. productId=" + pid);
    int before = inv.getOnHandQty();
    inv.ship(qty);
    transactionRepository.save(InventoryTransaction.of(
        pid, InventoryTransactionType.SHIP, -qty, before, inv.getOnHandQty(), "ORDER#" + orderId, null));
});
```
`releaseAll`은 기존 `applyFromLedger(..., Inventory::release)` 유지(onHand 미변경 → 원장 없음). `applyFromLedger` 헬퍼는 release만 쓰므로 그대로 둔다.
> `shipAll` 시그니처의 `orderId`를 참조에 사용. 이미 파라미터로 있음.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(wms): 출고를 SHIP 트랜잭션으로 기록 (reserved 이동은 제외)"
```

---

### Task 5: 시드 OPENING + 기존 데이터 백필 (기동 루틴, 멱등)

**Files:**
- Modify: `InitDb.java` (`InitService`), `repository/InventoryTransactionRepository.java`
- Test: `service/InventoryServiceTest.java` (또는 신규 `InitServiceTest`) — 여기서는 리포지토리 백필 쿼리를 `@DataJpaTest`로 검증

**Interfaces:**
- Produces (repository):
  - `int assignAdjustTypeToLegacy()` — `type IS NULL` 행을 ADJUST로
  - `boolean existsByProductIdAndType(Long productId, InventoryTransactionType type)`

- [ ] **Step 1: 리포지토리 백필 쿼리 추가**

`repository/InventoryTransactionRepository.java`:
```java
import com.jhg.wms.domain.InventoryTransactionType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// ...
@Modifying
@Query("update InventoryTransaction t set t.type = com.jhg.wms.domain.InventoryTransactionType.ADJUST where t.type is null")
int assignAdjustTypeToLegacy();

boolean existsByProductIdAndType(Long productId, InventoryTransactionType type);
```

- [ ] **Step 2: 실패 테스트 작성 (백필 멱등성)**

`service/InventoryServiceTest.java`에 추가(리포지토리 직접 검증):
```java
@Test
void legacy_백필_후_OPENING이_상품당_하나만_생긴다() {
    seed(1L, 30);
    // 기존 조정 이력(type null) 흉내: of(...)로 저장 후 native/JPQL 백필
    adjustmentRepo.save(com.jhg.wms.domain.InventoryTransaction.of(1L, null, -1, 31, 30, null, "구데이터"));
    // when: 백필 두 번
    adjustmentRepo.assignAdjustTypeToLegacy();
    if (!adjustmentRepo.existsByProductIdAndType(1L, InventoryTransactionType.OPENING))
        adjustmentRepo.save(com.jhg.wms.domain.InventoryTransaction.of(1L, InventoryTransactionType.OPENING, 30, 0, 30, null, null));
    if (!adjustmentRepo.existsByProductIdAndType(1L, InventoryTransactionType.OPENING))
        adjustmentRepo.save(com.jhg.wms.domain.InventoryTransaction.of(1L, InventoryTransactionType.OPENING, 30, 0, 30, null, null));
    // then
    var all = adjustmentRepo.findAllByOrderByIdDesc();
    assertThat(all).noneMatch(t -> t.getType() == null);            // 구데이터 ADJUST로
    assertThat(all.stream().filter(t -> t.getType() == InventoryTransactionType.OPENING)).hasSize(1);
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: FAIL (쿼리 메서드 없음 → 컴파일/실행 실패).

- [ ] **Step 4: `InitService`에 시드 OPENING + 마이그레이션 배선**

`InitDb.java`의 `InitService`:
- 필드 추가: `private final InventoryTransactionRepository transactionRepository;`
- `seed()`에서 재고 저장 직후 OPENING 기록:
```java
public void seed() {
    for (int i = 0; i < 20; i++) {
        long productId = i + 1;
        int onHandQty = 15 * (i + 1);
        String productName = "상품 " + productId;
        inventoryRepository.save(Inventory.create(productId, productName, onHandQty));
        transactionRepository.save(InventoryTransaction.of(
            productId, InventoryTransactionType.OPENING, onHandQty, 0, onHandQty, null, null));
    }
}
```
- 마이그레이션 메서드 추가:
```java
/** 기존 배포분 보정(멱등): 구 조정행 type 백필 + 재고별 OPENING 소급. */
public void migrateLegacy() {
    transactionRepository.assignAdjustTypeToLegacy();
    inventoryRepository.findAll().forEach(inv -> {
        if (!transactionRepository.existsByProductIdAndType(inv.getProductId(), InventoryTransactionType.OPENING))
            transactionRepository.save(InventoryTransaction.of(
                inv.getProductId(), InventoryTransactionType.OPENING, inv.getOnHandQty(), 0, inv.getOnHandQty(), null, null));
    });
}
```
- `seedIfNeeded()`의 이미-시드 분기에서 호출:
```java
if (initService.alreadySeeded()) {
    initService.backfillNames();
    initService.migrateLegacy();   // ← 추가
    log.info("[{}] 재고 이미 시드됨 — 시딩 skip", instanceId);
    return;
}
```
import에 `InventoryTransaction`, `InventoryTransactionType`, `InventoryTransactionRepository` 추가.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(wms): 시드 OPENING 기록 + 기존 배포분 멱등 백필(type·OPENING)"
```

---

### Task 6: 재구성 불변식 테스트 (완료 기준 capstone)

원장 델타 합이 현재 onHand와 같음을 증명한다.

**Files:**
- Test: `service/InventoryServiceTest.java`

- [ ] **Step 1: 불변식 테스트 작성**

```java
@Test
void 원장_델타합이_현재_onHand와_같다() {
    seed(1L, 0);
    service.applyDelta(1L, 100, InventoryTransactionType.OPENING, null, null); // 100
    service.applyDelta(1L, 50, InventoryTransactionType.RECEIVE, "PO#1", null); // 150
    service.reserveAll(10L, Map.of(1L, 30));
    service.shipAll(10L, Map.of(1L, 30));                                       // 120
    service.adjust(1L, -5, "파손");                                            // 115

    int deltaSum = adjustmentRepo.findAllByOrderByIdDesc().stream()
            .filter(t -> t.getProductId() == 1L)
            .mapToInt(t -> t.getDelta()).sum();
    int onHand = repo.findByProductIdIn(List.of(1L)).get(0).getOnHandQty();
    assertThat(deltaSum).isEqualTo(onHand);   // 115
    assertThat(onHand).isEqualTo(115);
}
```

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew test --tests InventoryServiceTest`
Expected: PASS (Task 2~5 완성 시 green).

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "test(wms): 원장 재구성 불변식(Σdelta==onHand) 검증"
```

---

### Task 7: 관리자 화면 — 트랜잭션 이력 + type 필터

**Files:**
- Modify: `service/InventoryService.java`, `web/WmsAdminController.java`, `templates/admin/inventory.html`
- Test: `web/WmsAdminControllerTest.java`

**Interfaces:**
- Produces: `List<InventoryTransaction> findAllTransactions()` (구 `findAllAdjustments` 리네임)

- [ ] **Step 1: 서비스 메서드 리네임**

`service/InventoryService.java`:
```java
/** 관리자 화면용 재고 트랜잭션 이력(최신 먼저). */
public List<InventoryTransaction> findAllTransactions() {
    return transactionRepository.findAllByOrderByIdDesc();
}
```
구 `findAllAdjustments()` 제거.

- [ ] **Step 2: 컨트롤러 갱신 + type 필터**

`web/WmsAdminController.java` `inventory` 핸들러:
```java
@GetMapping("/admin/inventory")
public String inventory(@RequestParam(required = false) InventoryTransactionType type, Model model) {
    model.addAttribute("products", inventoryService.findAllRows());
    var txns = inventoryService.findAllTransactions();
    if (type != null) txns = txns.stream().filter(t -> t.getType() == type).toList();
    model.addAttribute("transactions", txns);
    model.addAttribute("filterType", type);
    return "admin/inventory";
}
```
import `com.jhg.wms.domain.InventoryTransactionType`, `com.jhg.wms.domain.InventoryTransaction` 필요 시 추가.

- [ ] **Step 3: 템플릿 갱신**

`templates/admin/inventory.html`의 "수동 조정 내역" 섹션(약 48~61줄)을 교체:
```html
<h3>재고 트랜잭션 이력</h3>
<p>
  <a href="/admin/inventory">전체</a>
  | <a href="/admin/inventory?type=OPENING">기초</a>
  | <a href="/admin/inventory?type=RECEIVE">입고</a>
  | <a href="/admin/inventory?type=SHIP">출고</a>
  | <a href="/admin/inventory?type=ADJUST">조정</a>
</p>
<table>
  <thead>
    <tr><th>일시</th><th>상품</th><th>유형</th><th>변동</th><th>참조</th><th>사유</th></tr>
  </thead>
  <tbody>
    <tr th:each="t : ${transactions}">
      <td th:text="${#temporals.format(t.createdAt, 'yyyy-MM-dd HH:mm')}">2026-07-21 15:00</td>
      <td th:text="${t.productId}">1</td>
      <td th:text="${t.type}">ADJUST</td>
      <td th:text="${(t.delta > 0 ? '+' : '') + t.delta}">+10</td>
      <td th:text="${t.reference}"></td>
      <td th:text="${t.reason}"></td>
    </tr>
    <tr th:if="${#lists.isEmpty(transactions)}">
      <td colspan="6">트랜잭션 없음</td>
    </tr>
  </tbody>
</table>
```

- [ ] **Step 4: 컨트롤러 슬라이스 테스트**

`web/WmsAdminControllerTest.java`에 추가(기존 인증·MockMvc 패턴 사용):
```java
@Test
void inventory_화면에_transactions_모델이_담긴다() throws Exception {
    mockMvc.perform(get("/admin/inventory").with(httpBasic("wms", "wms")))
           .andExpect(status().isOk())
           .andExpect(model().attributeExists("transactions"));
}
```
> 기존 테스트가 `adjustments` 속성을 검증하면 `transactions`로 수정.

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(wms): 관리자 재고 트랜잭션 이력 화면 + type 필터"
```

---

### Task 8: 문서 갱신 + 검증

**Files:**
- Modify: `README.md`
- Test: 수동 검증

- [ ] **Step 1: README 재고 상태 흐름·화면 설명 갱신**

`README.md`의 재고 상태 흐름 표/관리자 UI 절에 "수동 조정 내역" → "재고 트랜잭션 이력(OPENING/RECEIVE/SHIP/ADJUST)"을 반영하고, 원장 재구성 불변식 한 줄 추가.

- [ ] **Step 2: 전체 테스트 + 애플리케이션 기동 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전 테스트 green.
(선택) H2 서버 기동 후 `./gradlew bootRun`으로 시드 시 OPENING 20건 기록 확인.

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "docs(wms): 재고 트랜잭션 원장 반영 (README)"
```

---

## Self-Review

- **스펙 커버리지:** 1절 데이터모델→Task1 / 2절 기록무결성→Task2·3·4 / 3절 OPENING 백필→Task5 / 4절 prod 마이그레이션→Task5(테이블명 유지로 단순화) / 5절 조회화면→Task7 / 6절 테스트→Task2·4·5·6. 전 항목 매핑됨.
- **스펙 대비 변경:** 4절의 "테이블 리네임 + 크로스테이블 이관" → **"테이블명 유지 + 컬럼 추가 + NULL→ADJUST 백필"**로 대체(운영 리스크 감소, 외부 동작 동일). 스펙 문서에도 반영 필요 시 Task 8에서 한 줄.
- **타입 일관성:** `InventoryTransaction`·`InventoryTransactionType`·`InventoryTransactionRepository`·`applyDelta(Long,int,type,String,String)`·`findAllTransactions()` 전 태스크 일관.
- **플레이스홀더:** Task 3 테스트의 발주 생성부는 해당 파일 기존 패턴을 따르라는 지시(파일별 상이) — 코드 골격은 제시됨.
