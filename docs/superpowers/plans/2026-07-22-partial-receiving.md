# 부분 입고 (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한 발주를 여러 번에 나눠 입고할 수 있게 하고, 품목별 미도착 잔량을 보여준다.

**Architecture:** `PurchaseOrderItem`에 누적 입고량(`receivedQty`) 하나를 추가하고, `PurchaseOrder.receive(Map)`가 검증·누적·상태전이를 모두 맡은 뒤 **실제 반영된 delta를 반환**한다. 서비스는 그 반환값을 Phase 1의 `applyDelta`에 넘기기만 한다. 입고 건별 이력은 이미 Phase 1 원장(`RECEIVE`, `reference="PO#{id}"`)이 담당하므로 신규 엔티티를 만들지 않는다.

**Tech Stack:** Java 17 / Spring Boot 3 / Spring Data JPA (Hibernate 6) / Thymeleaf / JUnit 5 + AssertJ + Mockito / Gradle (Windows: `.\gradlew.bat`)

**Spec:** `docs/superpowers/specs/2026-07-22-partial-receiving-design.md`

## Global Constraints

- 상태 enum은 정확히 `ORDERED`, `PARTIALLY_RECEIVED`, `RECEIVED` 셋이다.
- 품목 식별은 **`purchaseOrderItemId`**로 한다. `productId`로 식별하지 않는다(한 발주에 같은 상품이 두 줄 가능).
- `PurchaseOrder.receive(Map<Long,Integer>)`의 반환은 **productId → delta**이며, 같은 상품은 **합산**하고 delta가 0인 항목은 **담지 않는다**.
- 재고 반영은 반드시 `inventoryService.applyDelta(productId, delta, InventoryTransactionType.RECEIVE, "PO#" + poId, null)`를 쓴다. `InventoryService.adjust(...)`는 타입이 `ADJUST`로 고정된 관리자 수동 조정 전용 래퍼이므로 **사용 금지**.
- `ReplenishmentRequest::fulfill`은 발주 상태가 `RECEIVED`가 된 경우에만 호출한다.
- `qty == 0`은 허용(이번에 안 온 품목), `qty < 0`은 거부, `qty > remainingQty()`는 거부, 입력이 **전부 0이면** 거부.
- `receivedAt`은 **전량 완료 시각**이다. 부분 입고 중에는 `null`을 유지한다.
- 검증은 도메인에만 둔다. 서비스·컨트롤러에 수량 검증 로직을 넣지 않는다.
- `Inventory`, `InventoryTransaction`, `Reservation`, `ReplenishmentRequest` 도메인은 수정하지 않는다.
- 동시성 제어(`@Version`)는 이번 범위 밖 — 추가하지 않는다.
- 테스트 메서드명은 기존 관례대로 한글 스네이크(`receive_부분_입고하면_PARTIALLY_RECEIVED가_된다`)를 따른다.

## File Structure

**Modify**
- `src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java` — `PARTIALLY_RECEIVED` 추가
- `src/main/java/com/jhg/wms/domain/PurchaseOrderItem.java` — `receivedQty` + 잔량/검증 메서드
- `src/main/java/com/jhg/wms/domain/PurchaseOrder.java` — `receive(Map)` 시그니처 교체
- `src/main/java/com/jhg/wms/service/PurchaseOrderService.java` — `receive(poId, Map)`, `findWithItems(poId)`
- `src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java` — `findWithItemsById`
- `src/main/java/com/jhg/wms/InitDb.java` — 백필 호출
- `src/main/java/com/jhg/wms/web/WmsAdminController.java` — 상세 GET, 입고 POST 교체
- `src/main/resources/templates/admin/purchaseorders.html` — 3상태 표시·필터·진행도·링크

**Create**
- `src/main/java/com/jhg/wms/repository/PurchaseOrderItemRepository.java` — 백필 네이티브 쿼리
- `src/main/java/com/jhg/wms/web/ReceiveForm.java` — 입고 폼 바인딩
- `src/main/resources/templates/admin/purchaseorderdetail.html` — 발주 상세/입고 페이지

**Test**
- `src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java` (기존 수정 + 확장)
- `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java` (기존 수정 + 확장)
- `src/test/java/com/jhg/wms/InitDbTest.java` (확장)
- `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` (기존 수정 + 확장)

---

### Task 1: 도메인 — 누적 입고량과 부분 입고 전이

**Files:**
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java`
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrderItem.java`
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrder.java:43-48`
- Test: `src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `PurchaseOrderStatus.PARTIALLY_RECEIVED`
  - `PurchaseOrderItem#getReceivedQty(): int`, `#remainingQty(): int`, `#isFullyReceived(): boolean`
  - `PurchaseOrder#receive(Map<Long,Integer> qtyByItemId): Map<Long,Integer>` — 입력 itemId→이번 입고량, 반환 productId→delta

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`PurchaseOrderTest.java`를 아래 내용으로 **전체 교체**한다. 기존 두 테스트(`receive_ORDERED에서_RECEIVED로_전이한다`, `receive_이미_입고된_발주는_예외를_던진다`)는 인자 없는 `receive()`를 쓰므로 새 시그니처에 맞게 다시 쓴 것이 아래에 포함되어 있다.

도메인 단위 테스트에서는 엔티티가 영속화되지 않아 `id`가 `null`이다. `receive`가 itemId로 품목을 찾으므로, 테스트에서 `ReflectionTestUtils`로 id를 심어 준다.

```java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PurchaseOrderTest {

    /** 영속화 전이라 id가 null이므로 테스트에서 직접 심어 준다. */
    private static PurchaseOrderItem item(long itemId, long productId, int quantity) {
        PurchaseOrderItem item = PurchaseOrderItem.create(productId, quantity);
        ReflectionTestUtils.setField(item, "id", itemId);
        return item;
    }

    private static Map<Long, Integer> qty(long itemId, int quantity) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        map.put(itemId, quantity);
        return map;
    }

    @Test
    void create_발주는_ORDERED_상태로_생성된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급", item(1L, 1L, 10));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급");
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getItems().get(0).getReceivedQty()).isZero();
    }

    @Test
    void receive_전량_입고하면_RECEIVED로_전이한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 5));

        Map<Long, Integer> delta = po.receive(qty(1L, 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 5));
    }

    @Test
    void receive_부분_입고하면_PARTIALLY_RECEIVED가_되고_receivedAt은_null이다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 100));

        Map<Long, Integer> delta = po.receive(qty(1L, 60));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems().get(0).getReceivedQty()).isEqualTo(60);
        assertThat(po.getItems().get(0).remainingQty()).isEqualTo(40);
        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 60));
    }

    @Test
    void receive_잔량을_채우면_RECEIVED가_된다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 100));
        po.receive(qty(1L, 60));

        po.receive(qty(1L, 40));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(po.getItems().get(0).remainingQty()).isZero();
    }

    @Test
    void receive_qty0인_품목은_허용되지만_반환에_담기지_않는다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10), item(2L, 2L, 10));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 4);
        input.put(2L, 0);

        Map<Long, Integer> delta = po.receive(input);

        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 4));
        assertThat(po.getItems().get(1).getReceivedQty()).isZero();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void receive_같은_상품이_여러_줄이면_반환에서_합산된다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 7L, 10), item(2L, 7L, 5));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 3);
        input.put(2L, 2);

        Map<Long, Integer> delta = po.receive(input);

        assertThat(delta).containsExactlyEntriesOf(Map.of(7L, 5));
    }

    @Test
    void receive_잔량을_초과하면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 3L, 100));
        po.receive(qty(1L, 60));

        assertThatThrownBy(() -> po.receive(qty(1L, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잔량");
    }

    @Test
    void receive_음수는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(qty(1L, -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_전부_0이면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10), item(2L, 2L, 10));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 0);
        input.put(2L, 0);

        assertThatThrownBy(() -> po.receive(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입고 수량이 없습니다");
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void receive_빈_입력이면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_발주에_없는_품목ID는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(qty(999L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void receive_이미_입고완료된_발주는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 5));
        po.receive(qty(1L, 5));

        assertThatThrownBy(() -> po.receive(qty(1L, 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `.\gradlew.bat test --tests "*PurchaseOrderTest"`
Expected: 컴파일 실패 — `getReceivedQty()`, `remainingQty()`, `PARTIALLY_RECEIVED`, `receive(Map)`이 없음.

- [ ] **Step 3: `PurchaseOrderStatus`에 상태를 추가한다**

```java
package com.jhg.wms.domain;

public enum PurchaseOrderStatus { ORDERED, PARTIALLY_RECEIVED, RECEIVED }
```

- [ ] **Step 4: `PurchaseOrderItem`에 누적 입고량과 검증을 넣는다**

`private int quantity;` 아래에 필드를 추가하고, `create` 아래에 메서드를 추가한다:

```java
    private int receivedQty;   // 누적 입고량. 신규 생성 시 0.

    public int remainingQty() {
        return quantity - receivedQty;
    }

    public boolean isFullyReceived() {
        return remainingQty() == 0;
    }

    /** 이번 입고분을 누적한다. 0은 "이번에 안 온 품목"이라 허용한다. */
    void receive(int qty) {
        if (qty < 0)
            throw new IllegalArgumentException("상품#" + productId + ": 입고 수량은 0 이상이어야 합니다.");
        if (qty > remainingQty())
            throw new IllegalArgumentException(
                    "상품#" + productId + ": 잔량 " + remainingQty() + "개를 초과했습니다 (요청 " + qty + "개)");
        this.receivedQty += qty;
    }
```

- [ ] **Step 5: `PurchaseOrder.receive`를 교체한다**

`PurchaseOrder.java:43-48`의 기존 `receive()`를 아래로 **교체**한다. import에 `java.util.LinkedHashMap`, `java.util.Map`을 추가한다.

```java
    /**
     * 이번 입고분을 반영한다.
     * @param qtyByItemId 발주품목ID → 이번 입고량 (0 허용 — 이번에 안 온 품목)
     * @return 실제 반영된 productId → delta (0인 항목은 담기지 않고, 같은 상품은 합산된다)
     */
    public Map<Long, Integer> receive(Map<Long, Integer> qtyByItemId) {
        if (status == PurchaseOrderStatus.RECEIVED)
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");

        Map<Long, PurchaseOrderItem> byItemId = new LinkedHashMap<>();
        items.forEach(item -> byItemId.put(item.getId(), item));
        for (Long itemId : qtyByItemId.keySet())
            if (!byItemId.containsKey(itemId))
                throw new IllegalArgumentException("발주에 없는 품목입니다: itemId=" + itemId);

        // 상태를 바꾸기 전에 걸러낸다 — 빈 제출로 상태만 바뀌는 것을 막는다.
        if (qtyByItemId.values().stream().allMatch(qty -> qty == null || qty == 0))
            throw new IllegalArgumentException("입고 수량이 없습니다.");

        Map<Long, Integer> deltaByProductId = new LinkedHashMap<>();
        qtyByItemId.forEach((itemId, qty) -> {
            int received = qty == null ? 0 : qty;
            PurchaseOrderItem item = byItemId.get(itemId);
            item.receive(received);
            if (received > 0)
                deltaByProductId.merge(item.getProductId(), received, Integer::sum);
        });

        if (items.stream().allMatch(PurchaseOrderItem::isFullyReceived)) {
            this.status = PurchaseOrderStatus.RECEIVED;
            this.receivedAt = LocalDateTime.now();
        } else {
            this.status = PurchaseOrderStatus.PARTIALLY_RECEIVED;
        }
        return deltaByProductId;
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `.\gradlew.bat test --tests "*PurchaseOrderTest"`
Expected: PASS (12개 테스트). 다른 클래스는 아직 컴파일이 깨져 있을 수 있으므로 이 클래스만 돌린다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java
git commit -m "feat(wms): 발주 품목 누적 입고량 + 부분 입고 상태 전이"
```

---

### Task 2: 서비스 — 반환 delta만 원장에 반영, fulfill은 전량 시에만

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/PurchaseOrderService.java:40-50`
- Modify: `src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java`
- Test: `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Consumes: `PurchaseOrder#receive(Map<Long,Integer>): Map<Long,Integer>` (Task 1)
- Produces:
  - `PurchaseOrderService#receive(Long poId, Map<Long,Integer> qtyByItemId): Long`
  - `PurchaseOrderService#findWithItems(Long poId): PurchaseOrder`
  - `PurchaseOrderRepository#findWithItemsById(Long id): Optional<PurchaseOrder>`

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`PurchaseOrderServiceTest.java`의 기존 `receive_*` 테스트 6개(파일 71~137행 구간)를 아래로 **교체**한다. `create_*` 테스트는 그대로 둔다. 이 테스트는 `@DataJpaTest`라 발주가 실제로 저장되므로 품목 id를 조회해서 쓴다.

```java
    /** 저장된 발주의 n번째 품목 id를 꺼낸다. */
    private Long itemIdOf(Long poId, int index) {
        return poRepo.findById(poId).orElseThrow().getItems().get(index).getId();
    }

    @Test
    void receive_전량_입고하면_RECEIVED가_되고_재고가_늘어난다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 7)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 7));

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getItems().get(0).getReceivedQty()).isEqualTo(7);
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(17);
    }

    @Test
    void receive_부분_입고하면_들어온_수량만_재고와_원장에_반영된다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 100)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 60));

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(70);
        var receives = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.RECEIVE)
                .toList();
        assertThat(receives).hasSize(1);
        assertThat(receives.get(0).getDelta()).isEqualTo(60);
        assertThat(receives.get(0).getReference()).isEqualTo("PO#" + poId);
    }

    @Test
    void receive_qty0인_품목은_원장에_행을_남기지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(2L, "상품 2", 10));
        Long poId = service.create(
                List.of(new PurchaseOrderLine(1L, 5), new PurchaseOrderLine(2L, 5)), "발주");
        var input = new java.util.LinkedHashMap<Long, Integer>();
        input.put(itemIdOf(poId, 0), 5);
        input.put(itemIdOf(poId, 1), 0);

        service.receive(poId, input);

        var receives = adjustmentRepo.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.RECEIVE)
                .toList();
        assertThat(receives).hasSize(1);
        assertThat(receives.get(0).getProductId()).isEqualTo(1L);
        assertThat(inventoryRepo.findByProductId(2L).orElseThrow().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void receive_잔량_초과면_재고도_원장도_변하지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");

        assertThatThrownBy(() -> service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 9)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(10);
        assertThat(adjustmentRepo.findAllByOrderByIdDesc()).isEmpty();
    }

    @Test
    void receive_없는_발주는_예외를_던진다() {
        assertThatThrownBy(() -> service.receive(999L, java.util.Map.of(1L, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_입고완료된_발주에_또_입고하면_예외를_던진다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");
        Long itemId = itemIdOf(poId, 0);
        service.receive(poId, java.util.Map.of(itemId, 5));

        assertThatThrownBy(() -> service.receive(poId, java.util.Map.of(itemId, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receive_입고하면_상품별로_OMS_통지를_예약한다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 5));

        verify(notifier).notifyAfterCommit(1L);
    }

    @Test
    void receive_부분_입고에서는_보충요청을_이행하지_않는다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");
        ReplenishmentRequest request = requestRepo.save(ReplenishmentRequest.create(
                UUID.randomUUID(), "저재고", ReplenishmentRequestItem.create(1L, 10)));
        request.approve(poId, "승인");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 4));

        assertThat(requestRepo.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ReplenishmentRequestStatus.APPROVED);
    }

    @Test
    void receive_전량_입고하면_보충요청이_이행된다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");
        ReplenishmentRequest request = requestRepo.save(ReplenishmentRequest.create(
                UUID.randomUUID(), "저재고", ReplenishmentRequestItem.create(1L, 10)));
        request.approve(poId, "승인");

        service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 10));

        assertThat(requestRepo.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ReplenishmentRequestStatus.FULFILLED);
    }

    @Test
    void findWithItems_품목까지_함께_조회한다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 3)), "발주");

        PurchaseOrder po = service.findWithItems(poId);

        assertThat(po.getId()).isEqualTo(poId);
        assertThat(po.getItems()).hasSize(1);
    }
```

**주의:** 기존 `receive_fulfillsLinkedReplenishmentRequest` 테스트가 쓰던 `ReplenishmentRequest.create(...)` / `approve(...)` 호출 형태는 그 파일의 기존 코드를 그대로 참고해 맞춘다(위 코드는 해당 패턴을 옮긴 것이므로, 시그니처가 다르면 **기존 파일의 것을 따른다**).

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `.\gradlew.bat test --tests "*PurchaseOrderServiceTest"`
Expected: 컴파일 실패 — `service.receive(Long, Map)`, `service.findWithItems(Long)` 없음.

- [ ] **Step 3: 리포지토리에 단건 fetch join을 추가한다**

`PurchaseOrderRepository.java`에 추가한다:

```java
    @Query("select distinct po from PurchaseOrder po left join fetch po.items where po.id = :id")
    Optional<PurchaseOrder> findWithItemsById(@Param("id") Long id);
```

import 추가: `java.util.Optional`, `org.springframework.data.repository.query.Param`.

- [ ] **Step 4: 서비스를 교체한다**

`PurchaseOrderService.java:40-50`의 `receive`를 아래로 교체하고, `findWithItems`를 `findAllWithItems` 옆에 추가한다. import에 `java.util.Map`, `com.jhg.wms.domain.PurchaseOrderStatus`를 추가한다.

```java
    @Transactional
    public Long receive(Long poId, Map<Long, Integer> qtyByItemId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));

        // 검증·누적·상태전이는 도메인이 하고, 여기선 실제 반영된 delta만 원장에 넘긴다.
        po.receive(qtyByItemId).forEach((productId, delta) ->
                inventoryService.applyDelta(productId, delta, InventoryTransactionType.RECEIVE,
                        "PO#" + poId, null));

        // 부분 입고 중에 이행 통지를 보내면 "요청 물량을 채웠다"는 거짓 신호가 된다.
        if (po.getStatus() == PurchaseOrderStatus.RECEIVED)
            requestRepository.findByPurchaseOrderId(poId).ifPresent(ReplenishmentRequest::fulfill);

        return po.getId();
    }

    public PurchaseOrder findWithItems(Long poId) {
        return purchaseOrderRepository.findWithItemsById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `.\gradlew.bat test --tests "*PurchaseOrderServiceTest" --tests "*PurchaseOrderTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/PurchaseOrderService.java src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java
git commit -m "feat(wms): 부분 입고 서비스 — 반영 delta만 원장 기록, 전량 시에만 이행"
```

---

### Task 3: 기존 배포분 `receivedQty` 백필

**Files:**
- Create: `src/main/java/com/jhg/wms/repository/PurchaseOrderItemRepository.java`
- Modify: `src/main/java/com/jhg/wms/InitDb.java:47-56`, `:58-102`
- Test: `src/test/java/com/jhg/wms/InitDbTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderItem#getReceivedQty()` (Task 1)
- Produces:
  - `PurchaseOrderItemRepository#backfillReceivedQtyForReceivedOrders(): int`
  - `PurchaseOrderItemRepository#backfillRemainingReceivedQty(): int`
  - `InitDb.InitService#migratePurchaseOrderItems(): void`
  - `InitDb.InitService` 생성자가 `(InventoryRepository, InventoryTransactionRepository, PurchaseOrderItemRepository)`로 바뀐다

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`InitDbTest.java`에 아래를 추가한다. `setUp()`의 생성자 호출도 인자 3개로 바꾼다.

```java
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
```

```java
    @Test
    void 발주품목_백필_입고완료는_발주량만큼_대기중은_0으로_채운다() {
        PurchaseOrder ordered = purchaseOrderRepository.save(
                PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10)));
        PurchaseOrder received = purchaseOrderRepository.save(
                PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 7)));
        received.receive(Map.of(received.getItems().get(0).getId(), 7));
        purchaseOrderRepository.flush();
        // 기존 배포분 재현: 컬럼이 막 추가된 직후처럼 NULL로 되돌린다.
        entityManager.createNativeQuery("update purchase_order_item set received_qty = null").executeUpdate();
        entityManager.clear();

        initService.migratePurchaseOrderItems();

        assertThat(purchaseOrderRepository.findById(ordered.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isZero();
        assertThat(purchaseOrderRepository.findById(received.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isEqualTo(7);
    }

    @Test
    void 발주품목_백필은_멱등이다() {
        PurchaseOrder po = purchaseOrderRepository.save(
                PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 7)));
        po.receive(Map.of(po.getItems().get(0).getId(), 3));   // 부분 입고 상태로 저장
        purchaseOrderRepository.flush();
        entityManager.clear();

        initService.migratePurchaseOrderItems();
        initService.migratePurchaseOrderItems();

        // 이미 값이 있으므로 백필이 건드리지 않는다 — 3이 7이나 0으로 덮이지 않는다.
        assertThat(purchaseOrderRepository.findById(po.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isEqualTo(3);
    }
```

`@Autowired EntityManager entityManager;` 필드와 다음 import를 추가한다:
`com.jhg.wms.domain.PurchaseOrder`, `com.jhg.wms.domain.PurchaseOrderItem`,
`com.jhg.wms.repository.PurchaseOrderRepository`, `com.jhg.wms.repository.PurchaseOrderItemRepository`,
`jakarta.persistence.EntityManager`, `java.util.Map`.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `.\gradlew.bat test --tests "*InitDbTest"`
Expected: 컴파일 실패 — `PurchaseOrderItemRepository`, `migratePurchaseOrderItems()` 없음.

- [ ] **Step 3: 백필 리포지토리를 만든다**

`src/main/java/com/jhg/wms/repository/PurchaseOrderItemRepository.java`:

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * receivedQty 도입 전 배포분 백필 전용 쿼리.
 * 네이티브 SQL을 쓰는 이유: receivedQty는 primitive int로 매핑돼 있어 JPQL의 `is null` 취급이
 * 구현체마다 다르고, 이 두 문장은 엔티티 로직이 아니라 스키마 마이그레이션이다.
 * clearAutomatically: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로, 안 비우면 후속 조회가 stale을 본다.
 */
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update purchase_order_item set received_qty = quantity
             where received_qty is null
               and purchase_order_id in (select purchase_order_id from purchase_order where status = 'RECEIVED')
            """, nativeQuery = true)
    int backfillReceivedQtyForReceivedOrders();

    @Modifying(clearAutomatically = true)
    @Query(value = "update purchase_order_item set received_qty = 0 where received_qty is null",
           nativeQuery = true)
    int backfillRemainingReceivedQty();
}
```

- [ ] **Step 4: `InitDb`에서 백필을 호출한다**

`InitService`에 필드와 메서드를 추가한다:

```java
        private final PurchaseOrderItemRepository purchaseOrderItemRepository;

        /** 기존 배포분 보정(멱등): receivedQty가 없던 발주 품목을 상태에서 유도해 채운다.
         *  입고완료 발주는 전량 입고된 것이고(구 receive()가 전량 처리만 했으므로 중간 상태는 존재할 수 없다),
         *  나머지는 0이다. PurchaseOrderItem을 처음 읽는 코드보다 반드시 먼저 돌아야 한다 —
         *  primitive int 필드에 NULL이 들어오면 하이드레이션에서 실패한다. */
        public void migratePurchaseOrderItems() {
            purchaseOrderItemRepository.backfillReceivedQtyForReceivedOrders();
            purchaseOrderItemRepository.backfillRemainingReceivedQty();
        }
```

`seedIfNeeded()`에서 **다른 백필보다 먼저** 호출한다:

```java
    private void seedIfNeeded() {
        initService.migratePurchaseOrderItems(); // 엔티티를 읽기 전에 — NULL이 primitive int로 들어오는 것 방지
        if (initService.alreadySeeded()) {
            initService.backfillNames();
            initService.migrateLegacy();
            log.info("[{}] 재고 이미 시드됨 — 시딩 skip", instanceId);
            return;
        }
        initService.seed();
        log.info("[{}] 재고 1~20 시드 완료", instanceId);
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `.\gradlew.bat test --tests "*InitDbTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/repository/PurchaseOrderItemRepository.java src/main/java/com/jhg/wms/InitDb.java src/test/java/com/jhg/wms/InitDbTest.java
git commit -m "feat(wms): 기존 배포분 receivedQty 멱등 백필"
```

---

### Task 4: 발주 상세/입고 화면

**Files:**
- Create: `src/main/java/com/jhg/wms/web/ReceiveForm.java`
- Create: `src/main/resources/templates/admin/purchaseorderdetail.html`
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java:99-108`
- Modify: `src/main/resources/templates/admin/purchaseorders.html:42-44`, `:48`, `:53`, `:55-57`, `:62-69`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderService#receive(Long, Map<Long,Integer>)`, `#findWithItems(Long)` (Task 2); `PurchaseOrderItem#remainingQty()`, `#getReceivedQty()` (Task 1)
- Produces: `GET /admin/purchase-orders/{poId}`, `POST /admin/purchase-orders/{poId}/receive`

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`WmsAdminControllerTest.java`에서 **먼저 기존 테스트를 고친다.** 119~121행이 인자 없는 `received.receive()`를 쓰므로 새 시그니처에 맞춘다. `PurchaseOrder.create`로 만든 품목은 id가 null이라 `ReflectionTestUtils`로 심어 준다:

```java
        PurchaseOrderItem receivedItem = PurchaseOrderItem.create(2L, 5);
        ReflectionTestUtils.setField(receivedItem, "id", 10L);
        PurchaseOrder received = PurchaseOrder.create("완료", receivedItem);
        received.receive(Map.of(10L, 5));
```

그리고 아래 테스트를 추가한다:

```java
    @Test
    void 발주_상세_페이지를_렌더링한다() throws Exception {
        PurchaseOrderItem item = PurchaseOrderItem.create(1L, 10);
        ReflectionTestUtils.setField(item, "id", 42L);
        PurchaseOrder po = PurchaseOrder.create("발주", item);
        when(purchaseOrderService.findWithItems(1L)).thenReturn(po);

        mockMvc.perform(get("/admin/purchase-orders/1").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/purchaseorderdetail"))
                .andExpect(model().attribute("po", po));
    }

    @Test
    void 입고_폼_제출은_품목별_수량을_서비스에_전달한다() throws Exception {
        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(httpBasic("wms", "wms")).with(csrf())
                        .param("items[0].itemId", "42")
                        .param("items[0].quantity", "6")
                        .param("items[1].itemId", "43")
                        .param("items[1].quantity", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders/1"));

        var expected = new java.util.LinkedHashMap<Long, Integer>();
        expected.put(42L, 6);
        expected.put(43L, 0);
        verify(purchaseOrderService).receive(1L, expected);
    }

    @Test
    void 입고_실패하면_에러_플래시를_담는다() throws Exception {
        doThrow(new IllegalArgumentException("잔량 40개를 초과했습니다"))
                .when(purchaseOrderService).receive(eq(1L), anyMap());

        mockMvc.perform(post("/admin/purchase-orders/1/receive").with(httpBasic("wms", "wms")).with(csrf())
                        .param("items[0].itemId", "42")
                        .param("items[0].quantity", "99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "잔량 40개를 초과했습니다"));
    }
```

import 추가: `org.springframework.test.util.ReflectionTestUtils`, `java.util.Map`, 그리고 정적 import `org.mockito.ArgumentMatchers.anyMap`, `org.mockito.ArgumentMatchers.eq`, `org.mockito.Mockito.doThrow`. 나머지 MockMvc 정적 import는 파일에 이미 있는 것을 쓴다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `.\gradlew.bat test --tests "*WmsAdminControllerTest"`
Expected: 실패 — 상세 GET/POST 매핑이 없어 404, 그리고 컴파일 오류.

- [ ] **Step 3: 폼 클래스를 만든다**

`src/main/java/com/jhg/wms/web/ReceiveForm.java` — 기존 `PurchaseOrderForm`과 같은 모양으로 맞춘다:

```java
package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class ReceiveForm {
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    public static class Item {
        private Long itemId;
        private int quantity;
    }
}
```

- [ ] **Step 4: 컨트롤러를 교체한다**

`WmsAdminController.java:99-108`의 기존 `receive` 핸들러를 **삭제**하고 아래 둘로 교체한다. 전량 입고 우회 경로(`POST /admin/purchase-orders/receive`)는 남기지 않는다.

```java
    @GetMapping("/admin/purchase-orders/{poId}")
    public String purchaseOrderDetail(@PathVariable Long poId, Model model) {
        model.addAttribute("po", purchaseOrderService.findWithItems(poId));
        return "admin/purchaseorderdetail";
    }

    @PostMapping("/admin/purchase-orders/{poId}/receive")
    public String receive(@PathVariable Long poId, @ModelAttribute ReceiveForm form, RedirectAttributes ra) {
        Map<Long, Integer> qtyByItemId = new LinkedHashMap<>();
        form.getItems().forEach(item -> qtyByItemId.merge(item.getItemId(), item.getQuantity(), Integer::sum));
        try {
            purchaseOrderService.receive(poId, qtyByItemId);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders/" + poId;
    }
```

import에 `java.util.LinkedHashMap`을 추가한다(`java.util.Map`은 이미 있다).

- [ ] **Step 5: 상세 템플릿을 만든다**

`src/main/resources/templates/admin/purchaseorderdetail.html`:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 발주 상세')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('purchase-orders')}"></nav>
<main>
  <h2>발주 #<span th:text="${po.id}">1</span></h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <p>
    상태:
    <span th:switch="${po.status.name()}">
      <span th:case="'RECEIVED'">입고완료</span>
      <span th:case="'PARTIALLY_RECEIVED'">부분입고</span>
      <span th:case="*">입고대기</span>
    </span>
    · 메모: <span th:text="${po.memo}">긴급</span>
    · 발주일시: <span th:text="${#temporals.format(po.createdAt,'yyyy-MM-dd HH:mm')}">2026-07-01 10:00</span>
    · 입고완료일시: <span th:text="${po.receivedAt != null} ? ${#temporals.format(po.receivedAt,'yyyy-MM-dd HH:mm')} : '—'">—</span>
  </p>

  <form th:action="@{'/admin/purchase-orders/' + ${po.id} + '/receive'}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
    <table>
      <thead>
        <tr><th>상품</th><th>발주량</th><th>입고량</th><th>잔량</th><th>이번 입고</th></tr>
      </thead>
      <tbody>
        <tr th:each="item, stat : ${po.items}">
          <td th:text="|상품#${item.productId}|">상품#1</td>
          <td th:text="${item.quantity}">100</td>
          <td th:text="${item.receivedQty}">60</td>
          <td th:text="${item.remainingQty()}">40</td>
          <td>
            <span th:if="${item.fullyReceived}">완료</span>
            <span th:unless="${item.fullyReceived}">
              <input type="hidden" th:name="|items[${stat.index}].itemId|" th:value="${item.id}" />
              <input type="number" th:name="|items[${stat.index}].quantity|"
                     min="0" th:max="${item.remainingQty()}" value="0" style="width:80px" />
            </span>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="form-row" th:if="${po.status.name() != 'RECEIVED'}">
      <button type="submit">입고 처리</button>
    </div>
  </form>

  <p><a th:href="@{/admin/purchase-orders}">← 발주 목록</a></p>
</main>
</body>
</html>
```

- [ ] **Step 6: 목록 템플릿을 고친다**

`purchaseorders.html`에서 네 군데를 고친다.

필터 탭(`:42-44` 뒤)에 부분입고를 추가:

```html
    <a th:href="@{/admin/purchase-orders(status='PARTIALLY_RECEIVED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'PARTIALLY_RECEIVED'} ? 'active'">부분입고</a>
```

상태 칸(`:53`)을 3상태로:

```html
        <td th:switch="${po.status.name()}">
          <span th:case="'RECEIVED'">입고완료</span>
          <span th:case="'PARTIALLY_RECEIVED'">부분입고</span>
          <span th:case="*">입고대기</span>
        </td>
```

품목 칸(`:55-57`)을 진행도로:

```html
          <span th:each="item, stat : ${po.items}">
            <span th:text="|상품#${item.productId} ${item.receivedQty}/${item.quantity}|">상품#1 60/100</span><span th:if="${!stat.last}">, </span>
          </span>
```

입고 버튼(`:62-69`)을 상세 링크로:

```html
        <td>
          <a class="sm" th:href="@{'/admin/purchase-orders/' + ${po.id}}">상세</a>
        </td>
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `.\gradlew.bat test --tests "*WmsAdminControllerTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/web src/main/resources/templates/admin src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "feat(wms): 발주 상세·부분 입고 화면 + 목록 진행도 표시"
```

---

### Task 5: 문서 갱신 + 전체 검증

**Files:**
- Modify: `README.md`
- Test: 전체 스위트

**Interfaces:**
- Consumes: Task 1~4의 모든 산출물
- Produces: 없음

- [ ] **Step 1: README를 갱신한다**

`README.md`에서 발주 흐름을 설명하는 절을 찾아, 전량 입고 전제로 쓰인 서술을 부분 입고로 고친다. 최소한 다음 세 가지가 반영되어야 한다:

1. 발주 상태가 `ORDERED → PARTIALLY_RECEIVED → RECEIVED` 세 단계라는 것
2. 한 발주를 여러 번 나눠 입고할 수 있고 품목별 잔량이 남는다는 것
3. 보충요청 이행(`FULFILLED`)은 **전량 입고 시에만** 일어난다는 것

**실제 코드를 읽고 확인한 내용만 쓴다.** 기존 서술이 `POST /admin/purchase-orders/receive`(삭제된 엔드포인트)를 언급하고 있으면 `/admin/purchase-orders/{poId}/receive`로 고친다.

- [ ] **Step 2: 전체 테스트를 돌린다**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL, 전 테스트 green.

실패하면 고친다. 특히 `PurchaseOrder.receive()`를 인자 없이 부르던 테스트가 남아 있지 않은지 확인한다.

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs(wms): 부분 입고 반영 (README)"
```

---

## Self-Review

**스펙 커버리지:** 1절 데이터모델 → Task 1 / 2절 입고흐름 → Task 1(도메인)·Task 2(서비스) / 3절 마이그레이션 → Task 3 / 4절 UI → Task 4 / 5절 OMS 영향 → 코드 변경 없음(검증 대상 아님, Task 2의 fulfill 조건 테스트가 유일한 관측 지점을 커버) / 6절 테스트 → Task 1~4에 분산. 전 항목 매핑됨.

**타입 일관성:** `receive(Map<Long,Integer>) → Map<Long,Integer>`(productId→delta), `remainingQty()`, `isFullyReceived()`, `getReceivedQty()`, `findWithItems(Long)`, `findWithItemsById(Long)`, `migratePurchaseOrderItems()`가 전 태스크에서 동일하게 쓰인다. 템플릿에서는 `isFullyReceived()`를 Thymeleaf 프로퍼티 표기 `${item.fullyReceived}`로 접근한다.

**알려진 지뢰:**
- `InitService` 생성자 인자가 2개 → 3개로 바뀐다. `InitDbTest.setUp()`이 이를 따라가야 한다(Task 3 Step 1).
- `PurchaseOrder.receive()`를 인자 없이 부르는 기존 테스트가 3곳 있다: `PurchaseOrderTest`(Task 1에서 전체 교체), `PurchaseOrderServiceTest`(Task 2에서 교체), `WmsAdminControllerTest:120`(Task 4 Step 1에서 수정). Task 5 Step 2의 전체 실행이 최종 그물이다.
- 도메인 단위 테스트는 id가 없는 엔티티를 다루므로 `ReflectionTestUtils.setField(item, "id", ...)`가 필수다.
