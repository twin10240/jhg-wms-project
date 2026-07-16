# Replenishment Request Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move manual inventory, purchase-order creation, and receiving authority out of OMS while adding an idempotent OMS-to-WMS replenishment request, approval, rejection, and fulfillment workflow.

**Architecture:** WMS owns `ReplenishmentRequest` and its four-state lifecycle. OMS renders inventory plus request history and submits a stable UUID idempotency key; WMS approval creates the existing `PurchaseOrder`, and receiving that PO fulfills the linked request in the same transaction before the existing after-commit callback promotes OMS backorders. Delivery uses an expand-contract sequence: WMS expansion, OMS switch, then WMS legacy REST cleanup.

**Tech Stack:** Java 21 WMS / Java 17 OMS, Spring Boot 3.5.5, Spring MVC, Spring Data JPA, Spring Security, Thymeleaf, RestClient, H2/PostgreSQL, JUnit 5, AssertJ, Mockito, MockMvc

---

## Repository and delivery map

- **WMS root:** `C:\study\jhg-wms-project`
- **OMS root:** `C:\study\jhg-commerce-project`
- **Source design:** `C:\study\jhg-wms-project\docs\superpowers\specs\2026-07-16-replenishment-request-workflow-design.md`
- **WMS expansion deploy candidate:** after Task 7; legacy OMS-facing write REST remains available.
- **OMS switch deploy candidate:** after Task 10; OMS no longer calls legacy write REST.
- **WMS cleanup deploy candidate:** after Task 11; legacy write REST is removed.

Do not remove or rename OMS→WMS `reserve/ship/release`, `/api/inventory/availability`, or `/api/inventory/rows`. Do not rename `OmsReplenishmentNotifier`; document it as the “재입고 통지 callback.”

## File structure

### WMS — create

- `src/main/java/com/jhg/wms/domain/ReplenishmentRequestStatus.java` — request lifecycle enum.
- `src/main/java/com/jhg/wms/domain/ReplenishmentRequest.java` — request aggregate and transitions.
- `src/main/java/com/jhg/wms/domain/ReplenishmentRequestItem.java` — immutable request line.
- `src/main/java/com/jhg/wms/repository/ReplenishmentRequestRepository.java` — key, PO, and history lookups.
- `src/main/java/com/jhg/wms/service/ReplenishmentRequestService.java` — validation, idempotent receipt, approval, and rejection.
- `src/main/java/com/jhg/wms/web/ReplenishmentRequestPayload.java` — OMS request JSON.
- `src/main/java/com/jhg/wms/web/ReplenishmentRequestResponse.java` — OMS history JSON.
- `src/main/java/com/jhg/wms/web/ReplenishmentRequestController.java` — authenticated `/api/replenishment-requests` API.
- `src/main/resources/templates/admin/replenishmentrequests.html` — WMS review screen.
- Matching domain, service, MVC, and security tests under `src/test/java/com/jhg/wms/**`.

### WMS — modify

- `src/main/java/com/jhg/wms/service/PurchaseOrderService.java` — fulfill an optional linked request during receive.
- `src/main/java/com/jhg/wms/web/WmsAdminController.java` — request list, approve, and reject actions.
- `src/main/resources/templates/fragments/layout.html` — navigation link to request review.
- `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`
- `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`
- `src/test/java/com/jhg/wms/config/SecurityConfigTest.java`

### OMS — create

- `src/main/java/com/jhg/hgpage/wms/dto/ReplenishmentRequestDto.java` — WMS response DTO.
- `src/main/java/com/jhg/hgpage/wms/adapter/WmsReplenishmentRequestAdapter.java` — create/list client with stable-key retry.
- `src/main/java/com/jhg/hgpage/wms/web/form/ReplenishmentRequestForm.java` — request form including hidden UUID.
- `src/test/java/com/jhg/hgpage/adapter/WmsReplenishmentRequestAdapterTest.java`

### OMS — modify

- `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java` — read inventory, submit requests, show history.
- `src/main/resources/templates/admin/inventory.html` — replace adjustment form with request form and history.
- `src/main/resources/templates/main.html` — replace the purchase-order link with the inventory/request screen.
- `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java` — remove only `adjust()`.
- `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`
- `src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java`

### Delete only after the OMS switch

- OMS: `WmsPurchaseOrderAdapter`, `PurchaseOrderDto`, `PurchaseOrderForm`, `admin/purchaseorders.html`, and their tests.
- WMS: `InventoryController.adjust`, `AdjustRequest`, `PurchaseOrderController`, `PurchaseOrderRequest`, `PurchaseOrderResponse`, and controller tests.

---

### Task 1: Add the WMS request aggregate

**Files:**
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\domain\ReplenishmentRequestStatus.java`
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\domain\ReplenishmentRequest.java`
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\domain\ReplenishmentRequestItem.java`
- Test: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\domain\ReplenishmentRequestTest.java`

- [ ] **Step 1: Write the failing aggregate tests**

```java
class ReplenishmentRequestTest {
    @Test
    void create_요청은_REQUESTED이고_중복품목을_거부한다() {
        ReplenishmentRequest request = ReplenishmentRequest.create(
                UUID.randomUUID(), "백오더 보충",
                ReplenishmentRequestItem.create(1L, 10),
                ReplenishmentRequestItem.create(2L, 5));

        assertThat(request.getStatus()).isEqualTo(ReplenishmentRequestStatus.REQUESTED);
        assertThat(request.getRequestedAt()).isNotNull();
        assertThat(request.getItems()).hasSize(2);
        assertThatThrownBy(() -> ReplenishmentRequest.create(
                UUID.randomUUID(), "중복",
                ReplenishmentRequestItem.create(1L, 10),
                ReplenishmentRequestItem.create(1L, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_reject_fulfill은_허용된_상태에서만_전이한다() {
        ReplenishmentRequest approved = request();
        approved.approve(7L, "승인");
        assertThat(approved.getStatus()).isEqualTo(ReplenishmentRequestStatus.APPROVED);
        assertThat(approved.getPurchaseOrderId()).isEqualTo(7L);
        approved.fulfill();
        assertThat(approved.getStatus()).isEqualTo(ReplenishmentRequestStatus.FULFILLED);
        assertThat(approved.getFulfilledAt()).isNotNull();

        ReplenishmentRequest rejected = request();
        assertThatThrownBy(() -> rejected.reject(" "))
                .isInstanceOf(IllegalArgumentException.class);
        rejected.reject("재고 충분");
        assertThat(rejected.getStatus()).isEqualTo(ReplenishmentRequestStatus.REJECTED);
        assertThatThrownBy(() -> rejected.approve(8L, "재승인"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run from `C:\study\jhg-wms-project`:

```powershell
.\gradlew.bat test --tests "com.jhg.wms.domain.ReplenishmentRequestTest"
```

Expected: FAIL because the three request domain types do not exist.

- [ ] **Step 3: Implement the minimal aggregate**

Use `REQUESTED`, `APPROVED`, `REJECTED`, and `FULFILLED` in the enum. In `ReplenishmentRequest` use JPA fields matching the design, including database constraints:

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequest {
    @Id @GeneratedValue
    @Column(name = "replenishment_request_id")
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID requestKey;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplenishmentRequestStatus status;

    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;
    private LocalDateTime fulfilledAt;
    private String wmsMemo;

    @Column(unique = true)
    private Long purchaseOrderId;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReplenishmentRequestItem> items = new ArrayList<>();

    public static ReplenishmentRequest create(UUID key, String reason,
                                               ReplenishmentRequestItem... items) {
        if (key == null || reason == null || reason.isBlank())
            throw new IllegalArgumentException("요청 키와 사유는 필수입니다.");
        if (items == null || items.length == 0)
            throw new IllegalArgumentException("요청 품목이 없습니다.");
        if (Arrays.stream(items).map(ReplenishmentRequestItem::getProductId).distinct().count() != items.length)
            throw new IllegalArgumentException("같은 품목을 중복 요청할 수 없습니다.");
        ReplenishmentRequest request = new ReplenishmentRequest();
        request.requestKey = key;
        request.reason = reason.trim();
        request.status = ReplenishmentRequestStatus.REQUESTED;
        request.requestedAt = LocalDateTime.now();
        for (ReplenishmentRequestItem item : items) {
            request.items.add(item);
            item.setRequest(request);
        }
        return request;
    }

    public void approve(Long poId, String memo) {
        requireRequested();
        if (poId == null) throw new IllegalArgumentException("발주 번호는 필수입니다.");
        status = ReplenishmentRequestStatus.APPROVED;
        purchaseOrderId = poId;
        wmsMemo = memo == null ? "" : memo.trim();
        decidedAt = LocalDateTime.now();
    }

    public void reject(String memo) {
        requireRequested();
        if (memo == null || memo.isBlank()) throw new IllegalArgumentException("반려 메모는 필수입니다.");
        status = ReplenishmentRequestStatus.REJECTED;
        wmsMemo = memo.trim();
        decidedAt = LocalDateTime.now();
    }

    public void fulfill() {
        if (status != ReplenishmentRequestStatus.APPROVED)
            throw new IllegalStateException("승인된 요청만 충족 처리할 수 있습니다.");
        status = ReplenishmentRequestStatus.FULFILLED;
        fulfilledAt = LocalDateTime.now();
    }

    private void requireRequested() {
        if (status != ReplenishmentRequestStatus.REQUESTED)
            throw new IllegalStateException("이미 처리된 보충 요청입니다.");
    }
}
```

Implement the line entity as follows; `setRequest` remains package-private so only the aggregate wires ownership:

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequestItem {
    @Id @GeneratedValue
    @Column(name = "replenishment_request_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replenishment_request_id", nullable = false)
    private ReplenishmentRequest request;

    @Column(nullable = false)
    private Long productId;

    private int requestedQty;

    public static ReplenishmentRequestItem create(Long productId, int requestedQty) {
        if (productId == null) throw new IllegalArgumentException("productId는 필수입니다.");
        if (requestedQty < 1) throw new IllegalArgumentException("요청 수량은 1 이상이어야 합니다.");
        ReplenishmentRequestItem item = new ReplenishmentRequestItem();
        item.productId = productId;
        item.requestedQty = requestedQty;
        return item;
    }

    void setRequest(ReplenishmentRequest request) {
        this.request = request;
    }
}
```

- [ ] **Step 4: Run the aggregate test and verify GREEN**

Run the same Gradle command. Expected: PASS.

- [ ] **Step 5: Commit the aggregate**

```powershell
git add src/main/java/com/jhg/wms/domain/ReplenishmentRequest*.java src/test/java/com/jhg/wms/domain/ReplenishmentRequestTest.java
git commit -m "feat: add replenishment request lifecycle"
```

---

### Task 2: Persist and idempotently receive WMS requests

**Files:**
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\repository\ReplenishmentRequestRepository.java`
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\service\ReplenishmentRequestService.java`
- Test: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\service\ReplenishmentRequestServiceTest.java`

- [ ] **Step 1: Write failing service tests for validation and idempotency**

Cover these named cases in a `@DataJpaTest` using real `InventoryRepository` and `ReplenishmentRequestRepository`. Disable the test method's ambient transaction with `@Transactional(propagation = Propagation.NOT_SUPPORTED)` and clean committed rows in `@AfterEach`; this must exercise the same transaction boundaries as production rather than hiding a rollback-only transaction inside the default test transaction:

```java
@Test
void request_같은키와_같은내용은_기존행을_반환한다() {
    UUID key = UUID.randomUUID();
    RequestResult first = service.request(key, "백오더", List.of(new RequestLine(1L, 10)));
    RequestResult second = service.request(key, "백오더", List.of(new RequestLine(1L, 10)));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.request().getId()).isEqualTo(first.request().getId());
    assertThat(requestRepository.count()).isEqualTo(1);
}

@Test
void request_같은키와_다른내용은_충돌한다() {
    UUID key = UUID.randomUUID();
    service.request(key, "백오더", List.of(new RequestLine(1L, 10)));
    assertThatThrownBy(() -> service.request(key, "변경", List.of(new RequestLine(1L, 10))))
            .isInstanceOf(IllegalStateException.class);
}
```

Also test missing inventory, empty reason/items, non-positive quantity, duplicate product ID, and assert that no transaction is active before calling `request()`. Add repository tests that flush and clear the persistence context, call both fetch queries below, and assert `Hibernate.isInitialized(request.getItems())` is true. These checks guard the API response mapper and Thymeleaf rendering while `spring.jpa.open-in-view=false`.

- [ ] **Step 2: Run the service test and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.service.ReplenishmentRequestServiceTest"
```

Expected: FAIL because repository and service do not exist.

- [ ] **Step 3: Add repository lookups**

```java
public interface ReplenishmentRequestRepository extends JpaRepository<ReplenishmentRequest, Long> {
    @Query("""
            select distinct r from ReplenishmentRequest r
            left join fetch r.items
            where r.requestKey = :requestKey
            """)
    Optional<ReplenishmentRequest> findByRequestKeyWithItems(@Param("requestKey") UUID requestKey);

    Optional<ReplenishmentRequest> findByPurchaseOrderId(Long purchaseOrderId);

    @Query("""
            select distinct r from ReplenishmentRequest r
            left join fetch r.items
            order by r.id desc
            """)
    List<ReplenishmentRequest> findAllWithItems();
}
```

- [ ] **Step 4: Implement validation and stable-key convergence**

`ReplenishmentRequestService` exposes:

```java
public record RequestLine(Long productId, int requestedQty) {}
public record RequestResult(ReplenishmentRequest request, boolean created) {}

public RequestResult request(UUID key, String reason, List<RequestLine> lines)
public List<ReplenishmentRequest> findAll()
```

Normalize lines to a `LinkedHashMap<Long,Integer>` while rejecting duplicates. Verify all product IDs exist with `InventoryRepository.findByProductIdIn`. Compare an existing request using trimmed reason and `Map<productId, requestedQty>` so line order is irrelevant. `findAll()` must delegate to `findAllWithItems()` so callers can render item rows after the repository call returns.

Do **not** put class-level `@Transactional` on `ReplenishmentRequestService`. First look up `findByRequestKeyWithItems(key)` and reconcile the common idempotent retry before attempting an insert. Configure the `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, then use it around `saveAndFlush` so a concurrent UNIQUE loser cannot mark any caller transaction rollback-only and can be reconciled by reading the winning row:

```java
this.transactionTemplate = new TransactionTemplate(transactionManager);
this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

Optional<ReplenishmentRequest> existing = requestRepository.findByRequestKeyWithItems(key);
if (existing.isPresent()) return reconcile(existing.get(), reason, normalized);

try {
    ReplenishmentRequest saved = transactionTemplate.execute(status ->
            requestRepository.saveAndFlush(toEntity(key, reason, normalized)));
    return new RequestResult(saved, true);
} catch (DataIntegrityViolationException duplicate) {
    return reconcile(requestRepository.findByRequestKeyWithItems(key).orElseThrow(), reason, normalized);
}
```

`reconcile` returns `(existing, false)` for identical content and throws `IllegalStateException` for different content. Keep `request()` and read methods free of method-level `@Transactional`; Task 3 adds method-level transactions only to `approve()` and `reject()`.

Use these concrete helpers so later tasks share one signature:

```java
private LinkedHashMap<Long, Integer> normalize(List<RequestLine> lines) {
    if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("요청 품목이 없습니다.");
    LinkedHashMap<Long, Integer> normalized = new LinkedHashMap<>();
    for (RequestLine line : lines) {
        if (line.productId() == null || line.requestedQty() < 1)
            throw new IllegalArgumentException("품목과 1 이상의 수량은 필수입니다.");
        if (normalized.putIfAbsent(line.productId(), line.requestedQty()) != null)
            throw new IllegalArgumentException("같은 품목을 중복 요청할 수 없습니다.");
    }
    return normalized;
}

private void validateInventory(Set<Long> productIds) {
    Set<Long> found = inventoryRepository.findByProductIdIn(productIds).stream()
            .map(Inventory::getProductId).collect(Collectors.toSet());
    if (!found.equals(productIds)) throw new IllegalArgumentException("WMS에 없는 품목이 포함되어 있습니다.");
}

private RequestResult reconcile(ReplenishmentRequest existing, String reason,
                                Map<Long, Integer> normalized) {
    Map<Long, Integer> saved = existing.getItems().stream().collect(Collectors.toMap(
            ReplenishmentRequestItem::getProductId,
            ReplenishmentRequestItem::getRequestedQty));
    if (existing.getReason().equals(reason.trim()) && saved.equals(normalized))
        return new RequestResult(existing, false);
    throw new IllegalStateException("같은 requestKey에 다른 요청 내용이 있습니다.");
}

private ReplenishmentRequest toEntity(UUID key, String reason, Map<Long, Integer> normalized) {
    ReplenishmentRequestItem[] items = normalized.entrySet().stream()
            .map(e -> ReplenishmentRequestItem.create(e.getKey(), e.getValue()))
            .toArray(ReplenishmentRequestItem[]::new);
    return ReplenishmentRequest.create(key, reason, items);
}
```

- [ ] **Step 5: Run service tests and verify GREEN**

Run the Task 2 Gradle command. Expected: PASS.

- [ ] **Step 6: Commit request persistence**

```powershell
git add src/main/java/com/jhg/wms/repository/ReplenishmentRequestRepository.java src/main/java/com/jhg/wms/service/ReplenishmentRequestService.java src/test/java/com/jhg/wms/service/ReplenishmentRequestServiceTest.java
git commit -m "feat: receive replenishment requests idempotently"
```

---

### Task 3: Approve and reject requests in WMS

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\service\ReplenishmentRequestService.java`
- Modify: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\service\ReplenishmentRequestServiceTest.java`

- [ ] **Step 1: Write failing approval and rejection tests**

```java
@Test
void approve_요청라인으로_ORDERED_PO를_만들고_연결한다() {
    ReplenishmentRequest request = savedRequest(1L, 10);

    Long poId = service.approve(request.getId(), "승인");

    ReplenishmentRequest saved = requestRepository.findById(request.getId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ReplenishmentRequestStatus.APPROVED);
    assertThat(saved.getPurchaseOrderId()).isEqualTo(poId);
    assertThat(poRepository.findById(poId).orElseThrow().getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
}

@Test
void reject_메모를_기록하고_중복처리를_거부한다() {
    ReplenishmentRequest request = savedRequest(1L, 10);
    service.reject(request.getId(), "재고 충분");
    assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
            .isEqualTo(ReplenishmentRequestStatus.REJECTED);
    assertThatThrownBy(() -> service.approve(request.getId(), "재승인"))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run the Task 2 test command. Expected: FAIL because `approve` and `reject` do not exist.

- [ ] **Step 3: Implement transactional decisions**

Inject the existing `PurchaseOrderService`. Add:

```java
@Transactional
public Long approve(Long requestId, String memo) {
    ReplenishmentRequest request = findRequest(requestId);
    List<PurchaseOrderLine> lines = request.getItems().stream()
            .map(i -> new PurchaseOrderLine(i.getProductId(), i.getRequestedQty()))
            .toList();
    Long poId = purchaseOrderService.create(lines,
            "OMS 보충 요청 #" + request.getId() + " - " + request.getReason());
    request.approve(poId, memo);
    return poId;
}

@Transactional
public void reject(Long requestId, String memo) {
    findRequest(requestId).reject(memo);
}
```

Both PO creation and request approval must join one transaction. A repeated decision throws and rolls back any attempted PO creation.

- [ ] **Step 4: Run tests and verify GREEN**

Expected: all `ReplenishmentRequestServiceTest` tests PASS.

- [ ] **Step 5: Commit decisions**

```powershell
git add src/main/java/com/jhg/wms/service/ReplenishmentRequestService.java src/test/java/com/jhg/wms/service/ReplenishmentRequestServiceTest.java
git commit -m "feat: approve and reject replenishment requests"
```

---

### Task 4: Fulfill linked requests when a PO is received

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\service\PurchaseOrderService.java`
- Modify: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\service\PurchaseOrderServiceTest.java`

- [ ] **Step 1: Write failing receive tests**

Add `ReplenishmentRequestRepository` to the `@DataJpaTest` setup and cover both linked and direct POs:

```java
@Test
void receive_연결요청을_FULFILLED로_전이한다() {
    inventoryRepo.save(Inventory.create(1L, 5));
    Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "요청 발주");
    ReplenishmentRequest request = ReplenishmentRequest.create(
            UUID.randomUUID(), "백오더", ReplenishmentRequestItem.create(1L, 10));
    request.approve(poId, "승인");
    requestRepo.save(request);

    service.receive(poId);

    ReplenishmentRequest saved = requestRepo.findById(request.getId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ReplenishmentRequestStatus.FULFILLED);
    assertThat(saved.getFulfilledAt()).isNotNull();
}

@Test
void receive_연결요청이_없는_직접발주도_정상입고한다() {
    inventoryRepo.save(Inventory.create(1L, 0));
    Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "직접 발주");
    assertThatCode(() -> service.receive(poId)).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run `PurchaseOrderServiceTest` and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.service.PurchaseOrderServiceTest"
```

Expected: the linked request remains APPROVED.

- [ ] **Step 3: Fulfill only an optional linked request**

Inject `ReplenishmentRequestRepository` and add this after all inventory lines have been adjusted inside `receive`:

```java
requestRepository.findByPurchaseOrderId(poId)
        .ifPresent(ReplenishmentRequest::fulfill);
```

Do not use `orElseThrow`; direct WMS purchase orders intentionally have no request.

- [ ] **Step 4: Run service tests and verify GREEN**

Expected: existing receive, duplicate receive, callback scheduling, linked fulfillment, and direct PO tests all PASS.

- [ ] **Step 5: Commit fulfillment integration**

```powershell
git add src/main/java/com/jhg/wms/service/PurchaseOrderService.java src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java
git commit -m "feat: fulfill requests when purchase orders arrive"
```

---

### Task 5: Expose the authenticated WMS request API

**Files:**
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\ReplenishmentRequestPayload.java`
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\ReplenishmentRequestResponse.java`
- Create: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\ReplenishmentRequestController.java`
- Create: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\web\ReplenishmentRequestControllerTest.java`
- Modify: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\config\SecurityConfigTest.java`

- [ ] **Step 1: Write failing MVC tests**

Test POST new request → 201, idempotent repeat result → 200, conflict → 409, invalid payload → 400, GET history → 200, and unauthenticated → 401. API requests use `httpBasic("wms", "wms")` and no CSRF because `/api/**` is already excluded.

```java
mockMvc.perform(post("/api/replenishment-requests")
        .with(httpBasic("wms", "wms"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"requestKey":"00000000-0000-0000-0000-000000000001",
             "reason":"백오더","items":[{"productId":1,"requestedQty":10}]}
            """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REQUESTED"));
```

- [ ] **Step 2: Run controller tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.web.ReplenishmentRequestControllerTest" --tests "com.jhg.wms.config.SecurityConfigTest"
```

Expected: FAIL because endpoint types do not exist.

- [ ] **Step 3: Add request and response records**

```java
public record ReplenishmentRequestPayload(
        UUID requestKey, String reason, List<Item> items) {
    public record Item(Long productId, int requestedQty) {}
    public List<RequestLine> toLines() {
        return items == null ? List.of() : items.stream()
                .map(i -> new RequestLine(i.productId(), i.requestedQty())).toList();
    }
}
```

Implement the response mapper explicitly:

```java
public record ReplenishmentRequestResponse(
        Long id, UUID requestKey, String reason, String status,
        LocalDateTime requestedAt, LocalDateTime decidedAt, LocalDateTime fulfilledAt,
        String wmsMemo, Long purchaseOrderId, List<Item> items) {
    public record Item(Long productId, int requestedQty) {}

    public static ReplenishmentRequestResponse from(ReplenishmentRequest request) {
        return new ReplenishmentRequestResponse(
                request.getId(), request.getRequestKey(), request.getReason(),
                request.getStatus().name(), request.getRequestedAt(), request.getDecidedAt(),
                request.getFulfilledAt(), request.getWmsMemo(), request.getPurchaseOrderId(),
                request.getItems().stream()
                        .map(i -> new Item(i.getProductId(), i.getRequestedQty())).toList());
    }
}
```

- [ ] **Step 4: Implement POST/GET and status mapping**

```java
@RestController
@RequestMapping("/api/replenishment-requests")
@RequiredArgsConstructor
public class ReplenishmentRequestController {
    private final ReplenishmentRequestService service;

    @PostMapping
    public ResponseEntity<ReplenishmentRequestResponse> create(
            @RequestBody ReplenishmentRequestPayload payload) {
        try {
            RequestResult result = service.request(
                    payload.requestKey(), payload.reason(), payload.toLines());
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(ReplenishmentRequestResponse.from(result.request()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping
    public List<ReplenishmentRequestResponse> list() {
        return service.findAll().stream().map(ReplenishmentRequestResponse::from).toList();
    }
}
```

- [ ] **Step 5: Run API and security tests and verify GREEN**

Expected: all selected tests PASS, including unauthenticated API 401.

- [ ] **Step 6: Commit the request API**

```powershell
git add src/main/java/com/jhg/wms/web/ReplenishmentRequest*.java src/test/java/com/jhg/wms/web/ReplenishmentRequestControllerTest.java src/test/java/com/jhg/wms/config/SecurityConfigTest.java
git commit -m "feat: expose replenishment request API"
```

---

### Task 6: Add the WMS review screen

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\WmsAdminController.java`
- Create: `C:\study\jhg-wms-project\src\main\resources\templates\admin\replenishmentrequests.html`
- Modify: `C:\study\jhg-wms-project\src\main\resources\templates\fragments\layout.html`
- Modify: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\web\WmsAdminControllerTest.java`

- [ ] **Step 1: Write failing admin MVC tests**

Add a `@MockitoBean ReplenishmentRequestService` and test:

```java
mockMvc.perform(get("/admin/replenishment-requests").with(httpBasic("wms", "wms")))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/replenishmentrequests"))
        .andExpect(model().attributeExists("requests"));

mockMvc.perform(post("/admin/replenishment-requests/7/approve")
        .with(httpBasic("wms", "wms")).with(csrf()).param("wmsMemo", "승인"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/replenishment-requests"));
```

Also test reject without CSRF → 403 and a service `IllegalStateException` → error flash + redirect.

- [ ] **Step 2: Run admin tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.web.WmsAdminControllerTest"
```

- [ ] **Step 3: Add controller actions**

```java
@GetMapping("/admin/replenishment-requests")
public String replenishmentRequests(Model model) {
    model.addAttribute("requests", replenishmentRequestService.findAll());
    return "admin/replenishmentrequests";
}

@PostMapping("/admin/replenishment-requests/{id}/approve")
public String approveRequest(@PathVariable Long id,
                             @RequestParam(defaultValue = "") String wmsMemo,
                             RedirectAttributes ra) {
    try {
        Long poId = replenishmentRequestService.approve(id, wmsMemo);
        ra.addFlashAttribute("successMessage", "보충 요청을 승인했습니다. (발주 #" + poId + ")");
    } catch (IllegalArgumentException | IllegalStateException e) {
        ra.addFlashAttribute("errorMessage", e.getMessage());
    }
    return "redirect:/admin/replenishment-requests";
}
```

Add the symmetric reject action calling `service.reject(id, wmsMemo)`.

- [ ] **Step 4: Create the review template**

Use the existing `fragments/layout` head/nav/flash fragments. Render request number, status, items, reason, timestamps, WMS memo, and linked PO. Only REQUESTED rows render approve/reject forms; each form includes the CSRF hidden input. The new template body is:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 보충 요청')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('replenishment-requests')}"></nav>
<main>
  <h2>보충 요청 검토</h2>
  <div th:replace="~{fragments/layout :: flash}"></div>
  <table>
    <thead><tr><th>번호</th><th>상태</th><th>품목</th><th>요청 사유</th><th>요청 시각</th><th>처리</th></tr></thead>
    <tbody>
      <tr th:each="request : ${requests}">
        <td th:text="${request.id}">1</td>
        <td th:text="${request.status}">REQUESTED</td>
        <td><span th:each="item, stat : ${request.items}"><span th:text="|상품#${item.productId} x${item.requestedQty}|">상품#1 x10</span><span th:if="${!stat.last}">, </span></span></td>
        <td th:text="${request.reason}">백오더</td>
        <td th:text="${#temporals.format(request.requestedAt, 'yyyy-MM-dd HH:mm')}">2026-07-16 10:00</td>
        <td>
          <div th:if="${request.status.name() == 'REQUESTED'}">
            <form th:action="@{|/admin/replenishment-requests/${request.id}/approve|}" method="post">
              <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
              <input name="wmsMemo" placeholder="승인 메모(선택)" />
              <button type="submit">승인</button>
            </form>
            <form th:action="@{|/admin/replenishment-requests/${request.id}/reject|}" method="post">
              <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
              <input name="wmsMemo" required placeholder="반려 사유" />
              <button type="submit">반려</button>
            </form>
          </div>
          <span th:unless="${request.status.name() == 'REQUESTED'}"
                th:text="${request.wmsMemo}">처리 완료</span>
        </td>
      </tr>
      <tr th:if="${#lists.isEmpty(requests)}"><td colspan="6">보충 요청이 없습니다.</td></tr>
    </tbody>
  </table>
</main>
</body>
</html>
```

Add `<a th:href="@{/admin/replenishment-requests}">보충 요청</a>` to `fragments/layout.html`.

- [ ] **Step 5: Run admin tests and verify GREEN**

Expected: authenticated GET 200, successful POST 3xx, service conflict flash + 3xx, and missing CSRF 403.

- [ ] **Step 6: Commit the WMS admin workflow**

```powershell
git add src/main/java/com/jhg/wms/web/WmsAdminController.java src/main/resources/templates/admin/replenishmentrequests.html src/main/resources/templates/fragments/layout.html src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "feat: review replenishment requests in WMS"
```

---

### Task 7: Verify and mark the WMS expansion deploy candidate

**Files:**
- Modify if needed: only files changed in Tasks 1–6.

- [ ] **Step 1: Run the full WMS test suite**

```powershell
.\gradlew.bat --no-daemon test
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Verify legacy compatibility remains**

```powershell
Get-ChildItem src/main/java -Recurse -File | Select-String -Pattern '/api/inventory/adjust|/api/purchase-orders'
```

Expected: `InventoryController.adjust` and `PurchaseOrderController` still exist in this deploy candidate; new request endpoints also exist.

- [ ] **Step 3: Smoke-test the expansion against the unchanged OMS**

Run the expanded WMS with the pre-switch OMS. From the existing OMS admin screens, verify one manual inventory adjustment and one purchase-order create/receive cycle still succeed. Also run one existing order through `reserve`, `ship`, and `release` as applicable. This proves the expansion commit remains backward compatible before any OMS switch is deployed.

- [ ] **Step 4: Record the expansion commit hash**

```powershell
git rev-parse --short HEAD
```

Record this hash in the execution notes as the **WMS expansion deploy candidate**. Do not delete the legacy endpoints before the OMS switch is ready.

---

### Task 8: Add the OMS replenishment request adapter

**Files:**
- Create: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\dto\ReplenishmentRequestDto.java`
- Create: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\adapter\WmsReplenishmentRequestAdapter.java`
- Create: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\adapter\WmsReplenishmentRequestAdapterTest.java`

- [ ] **Step 1: Write failing RestClient tests**

Cover POST JSON including the supplied UUID, Basic auth, GET history, a transient POST retry using the same body/key, WMS 400 → `IllegalArgumentException`, and WMS 409 or repeated transport failure → `IllegalStateException`.

```java
UUID key = UUID.fromString("00000000-0000-0000-0000-000000000001");
server.expect(requestTo("http://wms-test/api/replenishment-requests"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic d21zOndtcw=="))
      .andExpect(content().json("""
          {"requestKey":"00000000-0000-0000-0000-000000000001",
           "reason":"백오더","items":[{"productId":1,"requestedQty":10}]}
          """))
      .andRespond(withSuccess(requestJson, MediaType.APPLICATION_JSON));
```

- [ ] **Step 2: Run adapter tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsReplenishmentRequestAdapterTest"
```

- [ ] **Step 3: Add response DTO and adapter**

```java
public record ReplenishmentRequestDto(
        Long id, UUID requestKey, String reason, String status,
        LocalDateTime requestedAt, LocalDateTime decidedAt, LocalDateTime fulfilledAt,
        String wmsMemo, Long purchaseOrderId, List<ItemDto> items) {
    public record ItemDto(Long productId, int requestedQty) {}
}
```

The adapter exposes:

```java
public record RequestLine(Long productId, int requestedQty) {}
public ReplenishmentRequestDto create(UUID requestKey, List<RequestLine> items, String reason)
public List<ReplenishmentRequestDto> findAll()
```

Build `RestClient` with the same base URL and Basic credentials as existing WMS adapters. `create` constructs one immutable request body before calling `doCreate`; retry `ResourceAccessException` or WMS 5xx once with that same body. Map WMS 400 to `IllegalArgumentException`, 409 to `IllegalStateException`, and a second transport/5xx failure to `IllegalStateException("WMS 보충 요청 실패")`. `findAll` returns an empty list on `ResourceAccessException`, matching existing read-side fallback conventions.

Use this core implementation so the key cannot change between attempts:

```java
public ReplenishmentRequestDto create(UUID requestKey, List<RequestLine> items, String reason) {
    CreateRequest request = new CreateRequest(requestKey, reason, List.copyOf(items));
    try {
        return doCreate(request);
    } catch (ResourceAccessException | HttpServerErrorException first) {
        try {
            return doCreate(request);
        } catch (ResourceAccessException | HttpServerErrorException second) {
            throw new IllegalStateException("WMS 보충 요청 실패", second);
        }
    } catch (HttpClientErrorException e) {
        if (e.getStatusCode().value() == 409)
            throw new IllegalStateException("같은 요청 키에 다른 내용이 있습니다.", e);
        throw new IllegalArgumentException("WMS 보충 요청 거부: " + e.getStatusCode(), e);
    }
}

private ReplenishmentRequestDto doCreate(CreateRequest request) {
    ReplenishmentRequestDto response = restClient.post()
            .uri("/api/replenishment-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(ReplenishmentRequestDto.class);
    if (response == null) throw new IllegalStateException("WMS 보충 요청 응답이 없습니다.");
    return response;
}

public List<ReplenishmentRequestDto> findAll() {
    try {
        List<ReplenishmentRequestDto> response = restClient.get()
                .uri("/api/replenishment-requests")
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return response == null ? List.of() : response;
    } catch (ResourceAccessException e) {
        log.warn("WMS 연결 실패 — 보충 요청 이력 빈 목록으로 폴백: {}", e.getMessage());
        return List.of();
    }
}
```

- [ ] **Step 4: Run adapter tests and verify GREEN**

Expected: all adapter tests PASS and the retry expectations observe the same request key.

- [ ] **Step 5: Commit the OMS adapter**

```powershell
git add src/main/java/com/jhg/hgpage/wms/dto/ReplenishmentRequestDto.java src/main/java/com/jhg/hgpage/wms/adapter/WmsReplenishmentRequestAdapter.java src/test/java/com/jhg/hgpage/adapter/WmsReplenishmentRequestAdapterTest.java
git commit -m "feat: add WMS replenishment request client"
```

---

### Task 9: Replace OMS manual warehouse actions with the request UI

**Files:**
- Create: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\web\form\ReplenishmentRequestForm.java`
- Modify: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\web\controller\InventoryAdminController.java`
- Modify: `C:\study\jhg-commerce-project\src\main\resources\templates\admin\inventory.html`
- Modify: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\controller\admin\InventoryAdminControllerMvcTest.java`

- [ ] **Step 1: Rewrite MVC tests to express the new OMS role**

Remove WMS adjustment/PO adapter mocks and tests. Add `@MockitoBean WmsReplenishmentRequestAdapter` and test:

```java
@Test
void 재고화면은_재고와_보충요청이력과_새_requestKey를_제공한다() throws Exception {
    when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, 15)));
    when(requestAdapter.findAll()).thenReturn(List.of());

    mockMvc.perform(get("/admin/inventory").with(user(admin())))
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("products", "requests", "requestForm"));
}

@Test
void 보충요청은_폼의_requestKey를_그대로_전송한다() throws Exception {
    UUID key = UUID.fromString("00000000-0000-0000-0000-000000000001");
    mockMvc.perform(post("/admin/replenishment-requests")
            .with(user(admin())).with(csrf())
            .param("requestKey", key.toString())
            .param("items[0].productId", "1")
            .param("items[0].requestedQty", "10")
            .param("reason", "백오더"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/inventory"))
            .andExpect(flash().attributeExists("successMessage"));
    verify(requestAdapter).create(eq(key), anyList(), eq("백오더"));
}
```

Also test an adapter error preserves `requestForm` as a flash attribute with the same key, and a normal user receives 403.

- [ ] **Step 2: Run MVC tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"
```

- [ ] **Step 3: Add the form and controller flow**

```java
@Getter @Setter
public class ReplenishmentRequestForm {
    private UUID requestKey;
    private String reason;
    private List<Item> items = new ArrayList<>(List.of(new Item()));

    @Getter @Setter
    public static class Item {
        private Long productId;
        private int requestedQty;
    }
}
```

The GET action keeps a flashed form and otherwise creates a new key:

```java
@GetMapping("/admin/inventory")
public String inventory(Model model) {
    model.addAttribute("products", inventoryQueryAdapter.allRows());
    model.addAttribute("requests", requestAdapter.findAll());
    if (!model.containsAttribute("requestForm")) {
        ReplenishmentRequestForm form = new ReplenishmentRequestForm();
        form.setRequestKey(UUID.randomUUID());
        model.addAttribute("requestForm", form);
    }
    return "admin/inventory";
}
```

The POST action maps items, calls `requestAdapter.create(form.getRequestKey(), lines, form.getReason())`, and redirects. On `IllegalArgumentException` or `IllegalStateException`, flash both `errorMessage` and the unchanged `requestForm`; this preserves the idempotency key across a user-visible retry.

- [ ] **Step 4: Replace the inventory adjustment UI**

In `admin/inventory.html`:

- Change the description from manual adjustment to inventory visibility and replenishment requests.
- Replace the adjustment form with a `th:object="${requestForm}"` form posting to `/admin/replenishment-requests`.
- Include hidden `requestKey` and CSRF fields.
- Render product, requested quantity (min 1), and required reason.
- Add a request-history table for number, items, reason, status, requested/decided/fulfilled times, WMS memo, and linked PO.
- Remove links and wording that let OMS create POs or receive stock.

Replace the old sticky adjustment card with this form and add the history table after the inventory table:

```html
<div class="card sticky-tool">
  <h2>보충 요청</h2>
  <p class="page-desc">OMS는 재고 상태를 확인해 보충을 요청하고, WMS가 승인·발주·입고를 처리합니다.</p>
  <form th:action="@{/admin/replenishment-requests}" method="post" th:object="${requestForm}" class="adjust-grid">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
    <input type="hidden" th:field="*{requestKey}" />
    <div class="field">
      <label class="field-label">상품</label>
      <select th:field="*{items[0].productId}" class="input" required>
        <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
      </select>
    </div>
    <div class="field">
      <label class="field-label">요청 수량</label>
      <input class="input" type="number" min="1" th:field="*{items[0].requestedQty}" required />
    </div>
    <div class="field">
      <label class="field-label">요청 사유</label>
      <input class="input" type="text" th:field="*{reason}" required placeholder="백오더·판매 추세·안전재고 부족" />
    </div>
    <button class="btn" type="submit">보충 요청</button>
  </form>
</div>

<div class="card" style="margin-top:16px">
  <h2>보충 요청 이력</h2>
  <table>
    <thead><tr><th>번호</th><th>상태</th><th>품목</th><th>사유</th><th>WMS 메모</th><th>발주</th></tr></thead>
    <tbody>
      <tr th:each="request : ${requests}">
        <td th:text="${request.id}">1</td>
        <td th:text="${request.status}">REQUESTED</td>
        <td><span th:each="item, stat : ${request.items}"><span th:text="|상품#${item.productId} x${item.requestedQty}|">상품#1 x10</span><span th:if="${!stat.last}">, </span></span></td>
        <td th:text="${request.reason}">백오더</td>
        <td th:text="${request.wmsMemo}">-</td>
        <td th:text="${request.purchaseOrderId != null ? request.purchaseOrderId : '-'}">-</td>
      </tr>
      <tr th:if="${#lists.isEmpty(requests)}"><td colspan="6">보충 요청 이력이 없습니다.</td></tr>
    </tbody>
  </table>
</div>
```

- [ ] **Step 5: Run MVC tests and verify GREEN**

Expected: inventory/history GET, stable-key POST, preserved-key error, authorization, and CSRF behavior PASS.

- [ ] **Step 6: Commit the OMS role switch UI**

```powershell
git add src/main/java/com/jhg/hgpage/wms/web/form/ReplenishmentRequestForm.java src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java src/main/resources/templates/admin/inventory.html src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java
git commit -m "feat: request replenishment from OMS"
```

---

### Task 10: Remove the obsolete OMS warehouse-write surface

**Files:**
- Modify: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\adapter\WmsInventoryAdapter.java`
- Modify: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\adapter\WmsInventoryAdapterTest.java`
- Modify: `C:\study\jhg-commerce-project\src\main\resources\templates\main.html`
- Delete: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\adapter\WmsPurchaseOrderAdapter.java`
- Delete: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\dto\PurchaseOrderDto.java`
- Delete: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\web\form\PurchaseOrderForm.java`
- Delete: `C:\study\jhg-commerce-project\src\main\resources\templates\admin\purchaseorders.html`
- Delete: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\adapter\WmsPurchaseOrderAdapterTest.java`

- [ ] **Step 1: Verify exact legacy callers before deletion**

```powershell
Get-ChildItem src -Recurse -File | Select-String -Pattern 'WmsPurchaseOrderAdapter|PurchaseOrderDto|PurchaseOrderForm|/admin/purchase-orders|\.adjust\('
```

Expected: only the files listed in this task plus adjustment tests remain. Stop if an unexpected production caller appears.

- [ ] **Step 2: Remove only `WmsInventoryAdapter.adjust` and its two tests**

Keep the adapter constructor, `reserveAll`, `doReserve`, `shipAll`, and `releaseAll` unchanged. This preserves order fulfillment.

- [ ] **Step 3: Delete the OMS-only PO proxy files and update navigation**

Delete the files listed above. In `main.html`, replace the `/admin/purchase-orders` link with `/admin/inventory` and label it `재고·보충 요청`.

- [ ] **Step 4: Run targeted and full OMS tests**

```powershell
.\gradlew.bat --no-daemon test --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest" --tests "com.jhg.hgpage.adapter.WmsReplenishmentRequestAdapterTest" --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"
.\gradlew.bat --no-daemon test
```

Expected: both commands end with BUILD SUCCESSFUL and zero failed tests.

- [ ] **Step 5: Confirm protected order calls remain and old admin calls are gone**

```powershell
Select-String -Path src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java -Pattern 'reserve|ship|release|adjust'
Get-ChildItem src/main -Recurse -File | Select-String -Pattern '/admin/purchase-orders|/api/inventory/adjust|/api/purchase-orders'
```

Expected: reserve/ship/release remain; the second search returns no production matches.

- [ ] **Step 6: Commit the OMS cleanup**

```powershell
git add -- src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java src/main/java/com/jhg/hgpage/wms/adapter/WmsPurchaseOrderAdapter.java src/main/java/com/jhg/hgpage/wms/dto/PurchaseOrderDto.java src/main/java/com/jhg/hgpage/wms/web/form/PurchaseOrderForm.java src/main/resources/templates/admin/purchaseorders.html src/main/resources/templates/main.html src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java src/test/java/com/jhg/hgpage/adapter/WmsPurchaseOrderAdapterTest.java
git commit -m "refactor: remove OMS warehouse write controls"
git rev-parse --short HEAD
```

Record the hash as the **OMS switch deploy candidate**.

---

### Task 11: Remove legacy WMS REST after the OMS switch

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\InventoryController.java`
- Modify: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\web\InventoryControllerTest.java`
- Delete: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\AdjustRequest.java`
- Delete: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\PurchaseOrderController.java`
- Delete: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\PurchaseOrderRequest.java`
- Delete: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\PurchaseOrderResponse.java`
- Delete: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\web\PurchaseOrderControllerTest.java`

- [ ] **Step 1: Confirm the deployed OMS no longer calls legacy endpoints**

Run in `C:\study\jhg-commerce-project` at the OMS switch commit:

```powershell
Get-ChildItem src/main -Recurse -File | Select-String -Pattern '/api/inventory/adjust|/api/purchase-orders'
```

Expected: no matches. Do not continue cleanup if the deployed OMS is still on an older commit.

- [ ] **Step 2: Confirm WMS admin uses in-process services**

Run in `C:\study\jhg-wms-project`:

```powershell
Select-String -Path src/main/java/com/jhg/wms/web/WmsAdminController.java -Pattern 'inventoryService\.adjust|purchaseOrderService\.create|purchaseOrderService\.receive'
```

Expected: all three operations call services directly.

- [ ] **Step 3: Remove the obsolete REST surface**

Delete `InventoryController.adjust` only; retain availability, rows, reserve, ship, release, and exception handlers used by order writes. Delete `AdjustRequest`, the entire purchase-order REST controller and its two DTOs, and their MVC test. Remove only adjustment REST cases from `InventoryControllerTest`.

- [ ] **Step 4: Run targeted and full WMS tests**

```powershell
.\gradlew.bat --no-daemon test --tests "com.jhg.wms.web.InventoryControllerTest" --tests "com.jhg.wms.web.WmsAdminControllerTest" --tests "com.jhg.wms.web.ReplenishmentRequestControllerTest"
.\gradlew.bat --no-daemon test
```

Expected: both commands end with BUILD SUCCESSFUL and zero failed tests.

- [ ] **Step 5: Verify kept and removed endpoint text**

```powershell
Get-ChildItem src/main/java -Recurse -File | Select-String -Pattern '/api/inventory/adjust|/api/purchase-orders'
Select-String -Path src/main/java/com/jhg/wms/web/InventoryController.java -Pattern 'availability|rows|reserve|ship|release'
```

Expected: first command has no matches; second command shows all five retained operations.

- [ ] **Step 6: Commit the WMS cleanup**

```powershell
git add src/main/java/com/jhg/wms/web src/test/java/com/jhg/wms/web
git commit -m "refactor: remove obsolete warehouse write REST"
git rev-parse --short HEAD
```

Record the hash as the **WMS cleanup deploy candidate**.

---

### Task 12: Align documentation and run cross-service verification

**Files:**
- Modify: `C:\study\jhg-wms-project\README.md`
- Modify: `C:\study\jhg-commerce-project\CLAUDE.md`
- Modify: `C:\study\jhg-commerce-project\README.md`
- Modify only if existing risk entries mention the removed controls: `C:\study\jhg-commerce-project\risk.md`

- [ ] **Step 1: Update WMS documentation**

Document:

- WMS owns manual adjustment, purchase orders, receiving, and request history.
- OMS submits `ReplenishmentRequest`; WMS approves or rejects it.
- Approval creates ORDERED PO; receiving changes request to FULFILLED.
- `OmsReplenishmentNotifier` is the WMS→OMS “재입고 통지 callback.”

- [ ] **Step 2: Update OMS documentation**

Remove claims that OMS administrators adjust stock, create POs, or receive stock. Document the inventory-read + replenishment-request role and explicitly state that `reserve/ship/release` order fulfillment is unchanged.

- [ ] **Step 3: Run both full suites from clean task roots**

WMS:

```powershell
cd C:\study\jhg-wms-project
.\gradlew.bat --no-daemon test
```

OMS:

```powershell
cd C:\study\jhg-commerce-project
.\gradlew.bat --no-daemon test
```

Expected: both builds report BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 4: Run a two-service smoke test**

Start WMS on 8081 and OMS on 8080 using their existing local profiles and credentials. Verify in order:

1. OMS inventory page loads WMS rows and request history.
2. OMS submits one multi-line request and refresh/retry does not create a duplicate.
3. WMS review screen shows REQUESTED.
4. WMS approves it and shows a linked ORDERED PO; OMS history shows APPROVED.
5. WMS receives the PO; inventory increases and request becomes FULFILLED.
6. A matching OMS BACKORDERED order is promoted through the existing callback.
7. A direct WMS-created PO still receives successfully without a linked request.

- [ ] **Step 5: Commit documentation in each repository**

WMS:

```powershell
git add README.md
git commit -m "docs: document replenishment request workflow"
```

OMS:

```powershell
git add CLAUDE.md README.md risk.md
git commit -m "docs: align OMS inventory responsibilities"
```

If `risk.md` required no change, omit it from `git add` rather than touching it.

---

## Deployment checkpoint

Deploy only after Task 12 verification, using the recorded commits in this order:

1. The exact WMS expansion runtime commit recorded in Task 7; this commit intentionally contains no Task 12 documentation.
2. The OMS documentation commit from Task 12, which is a descendant of the Task 10 switch runtime commit. Record this final OMS deploy hash after the docs commit.
3. The WMS documentation commit from Task 12, which is a descendant of the Task 11 cleanup runtime commit. Record this final WMS cleanup deploy hash after the docs commit.

After each deployment, run the relevant smoke checks before moving to the next commit. If WMS expansion is deployed but OMS switch fails, the old OMS remains compatible because the old WMS endpoints still exist.
