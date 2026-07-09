# WMS 관리자 UI 확장 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 브라우저에서 WMS 현황(대시보드·재고·예약·발주)을 확인하고 처리하는 Thymeleaf 서버사이드 관리자 페이지.

**Architecture:** 기존 Thymeleaf MVC 패턴 그대로 확장. 공통 레이아웃은 fragment(`fragments/layout.html`) + 정적 CSS 하나. 폼은 POST → redirect → flash 패턴 유지. 대시보드·예약 화면 신규, 재고·발주 화면 보강, `error.html`로 Whitelabel 대체.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Thymeleaf, Spring Data JPA, JUnit5 + MockMvc(@WebMvcTest) + @DataJpaTest.

**스펙:** `docs/superpowers/specs/2026-07-09-wms-admin-ui-design.md`

## Global Constraints

- 의존성 추가 없음, 스키마 변경 없음, 인증 없음.
- 상품명 표시 안 함 — 상품ID만 사용.
- JS는 발주 다품목 행 추가/삭제용 바닐라 JS만 허용.
- 포인트 컬러 `#f4a261`. 상태 배지: RESERVED 파랑(#3a86ff), SHIPPED 초록(#2a9d8f), RELEASED 회색(#999).
- 테스트 실행: `./gradlew test --tests "<FQCN>"` (전체: `./gradlew test`).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: InventoryRowResponse에 예약·가용 수량 추가

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/InventoryRowResponse.java`
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java:48-53` (findAllRows)
- Modify: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java` (기존 findAllRows 테스트 보강)
- Modify: `src/test/java/com/jhg/wms/web/InventoryControllerTest.java:77-82` (record 생성자 변경 반영)

**Interfaces:**
- Produces: `record InventoryRowResponse(Long productId, int onHandQty, int reservedQty, int availableQty)` — 이후 모든 태스크의 템플릿·대시보드 집계가 이 4개 필드를 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`InventoryServiceTest`의 기존 `findAllRows_전체_재고행을_productId_오름차순으로_반환한다` 테스트 아래에 추가:

```java
@Test
void findAllRows_예약수량과_가용수량을_포함한다() {
    seed(1L, 10);
    service.reserveAll(99L, Map.of(1L, 3));
    var rows = service.findAllRows();
    assertThat(rows.get(0).onHandQty()).isEqualTo(10);
    assertThat(rows.get(0).reservedQty()).isEqualTo(3);
    assertThat(rows.get(0).availableQty()).isEqualTo(7);
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.jhg.wms.service.InventoryServiceTest"`
Expected: FAIL — `reservedQty()` 심볼 없음 (컴파일 에러)

- [ ] **Step 3: 구현**

`InventoryRowResponse.java` 전체 교체:

```java
package com.jhg.wms.web;

public record InventoryRowResponse(Long productId, int onHandQty, int reservedQty, int availableQty) {}
```

`InventoryService.findAllRows()` 수정:

```java
/** 관리자 재고 화면용 전체 목록. */
public List<InventoryRowResponse> findAllRows() {
    return inventoryRepository.findAll().stream()
            .map(inv -> new InventoryRowResponse(
                    inv.getProductId(), inv.getOnHandQty(), inv.getReservedQty(), inv.getAvailableQty()))
            .sorted(Comparator.comparing(InventoryRowResponse::productId))
            .toList();
}
```

`InventoryControllerTest.rows_전체_재고_목록을_반환한다` 수정 (생성자 인자 4개 + jsonPath 추가):

```java
@Test
void rows_전체_재고_목록을_반환한다() throws Exception {
    when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10, 3, 7)));

    mockMvc.perform(get("/api/inventory/rows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].productId").value(1))
            .andExpect(jsonPath("$[0].onHandQty").value(10))
            .andExpect(jsonPath("$[0].reservedQty").value(3))
            .andExpect(jsonPath("$[0].availableQty").value(7));
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test`
Expected: 전체 PASS (기존 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "feat(wms): InventoryRowResponse에 reservedQty·availableQty 추가 — 관리자 화면·REST rows 공용"
```

---

### Task 2: 공통 레이아웃(fragment)·admin.css + 재고 화면 보강

**Files:**
- Create: `src/main/resources/templates/fragments/layout.html`
- Create: `src/main/resources/static/css/admin.css`
- Modify: `src/main/resources/templates/admin/inventory.html` (전체 교체)
- Create: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `InventoryRowResponse(productId, onHandQty, reservedQty, availableQty)`.
- Produces: fragment 3개 — `fragments/layout :: head(title)`, `fragments/layout :: nav(active)` (active ∈ 'dashboard'|'inventory'|'reservations'|'purchase-orders'), `fragments/layout :: flash`. 이후 모든 템플릿이 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java` 신규:

```java
package com.jhg.wms.web;

import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WmsAdminController.class)
class WmsAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean PurchaseOrderService purchaseOrderService;

    @Test
    void 재고화면_보유_예약_가용_컬럼을_렌더링한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10, 3, 7)));

        mockMvc.perform(get("/admin/inventory"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(content().string(containsString("가용")))
                .andExpect(content().string(containsString("admin.css")));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: FAIL — 현재 inventory.html에 '가용' 컬럼과 admin.css 링크 없음

- [ ] **Step 3: 구현 — fragment + CSS + inventory.html**

`templates/fragments/layout.html` 신규:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:fragment="head(title)">
  <meta charset="UTF-8" />
  <title th:text="${title}">JHG-WMS</title>
  <link rel="stylesheet" th:href="@{/css/admin.css}" />
</head>
<body>
  <nav class="topnav" th:fragment="nav(active)">
    <span class="brand">JHG-WMS</span>
    <a th:href="@{/}" th:classappend="${active == 'dashboard'} ? 'active'">대시보드</a>
    <a th:href="@{/admin/inventory}" th:classappend="${active == 'inventory'} ? 'active'">재고</a>
    <a th:href="@{/admin/reservations}" th:classappend="${active == 'reservations'} ? 'active'">예약</a>
    <a th:href="@{/admin/purchase-orders}" th:classappend="${active == 'purchase-orders'} ? 'active'">발주</a>
  </nav>
  <div th:fragment="flash" th:remove="tag">
    <div class="flash-ok" th:if="${successMessage}" th:text="${successMessage}"></div>
    <div class="flash-err" th:if="${errorMessage}" th:text="${errorMessage}"></div>
  </div>
</body>
</html>
```

`static/css/admin.css` 신규 (기존 인라인 스타일 통합 + 신규 컴포넌트):

```css
body { font-family: sans-serif; margin: 0; background: #fafafa; }
main { max-width: 1000px; margin: 0 auto; padding: 24px; }

nav.topnav { display: flex; align-items: center; gap: 12px; background: #2b2d42; padding: 12px 24px; }
nav.topnav .brand { color: #f4a261; font-weight: bold; margin-right: 12px; }
nav.topnav a { color: #ddd; text-decoration: none; padding: 4px 10px; border-radius: 4px; }
nav.topnav a.active { background: #f4a261; color: #fff; }

table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { padding: 8px 12px; border: 1px solid #ddd; text-align: left; }
th { background: #f5f5f5; }
tr.soldout td { background: #fdecea; }

.flash-ok { color: green; padding: 8px; border: 1px solid green; margin-bottom: 12px; background: #fff; }
.flash-err { color: red; padding: 8px; border: 1px solid red; margin-bottom: 12px; background: #fff; }

.form-row { display: flex; gap: 8px; align-items: flex-end; margin-bottom: 16px; }
input, select { padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; }
button { padding: 7px 16px; background: #f4a261; color: white; border: none; border-radius: 4px; cursor: pointer; }
button.sm { padding: 4px 10px; font-size: 13px; }
button.ghost { background: #fff; color: #555; border: 1px solid #ccc; }

.cards { display: flex; gap: 16px; flex-wrap: wrap; }
.card { flex: 1 1 260px; background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 16px 20px; }
.card h3 { margin-top: 0; }
.card .big { font-size: 32px; font-weight: bold; margin: 4px 0 0; }
.card .label { color: #888; margin: 0 0 8px; }

.badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; color: #fff; }
.badge-reserved { background: #3a86ff; }
.badge-shipped { background: #2a9d8f; }
.badge-released { background: #999; }

.tabs { margin-bottom: 12px; }
.tabs a { margin-right: 8px; padding: 4px 10px; border: 1px solid #ccc; border-radius: 4px; text-decoration: none; color: #555; background: #fff; }
.tabs a.active { background: #f4a261; border-color: #f4a261; color: #fff; }
```

`templates/admin/inventory.html` 전체 교체:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 재고 관리')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('inventory')}"></nav>
<main>
  <h2>재고 관리</h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <h3>수동 재고 조정</h3>
  <form th:action="@{/admin/inventory/adjust}" method="post" class="form-row">
    <div>
      <label>상품 ID</label><br/>
      <select name="productId">
        <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
      </select>
    </div>
    <div>
      <label>증감 수량 (+/−)</label><br/>
      <input type="number" name="delta" value="-1" style="width:80px" />
    </div>
    <div>
      <label>사유</label><br/>
      <input type="text" name="reason" placeholder="정기실사 / 파손 등" />
    </div>
    <button type="submit">조정</button>
  </form>

  <h3>전체 재고</h3>
  <table>
    <thead><tr><th>상품 ID</th><th>보유</th><th>예약</th><th>가용</th></tr></thead>
    <tbody>
      <tr th:each="p : ${products}" th:classappend="${p.availableQty == 0} ? 'soldout'">
        <td th:text="${p.productId}">1</td>
        <td th:text="${p.onHandQty}">15</td>
        <td th:text="${p.reservedQty}">0</td>
        <td th:text="${p.availableQty}">15</td>
      </tr>
      <tr th:if="${#lists.isEmpty(products)}">
        <td colspan="4">재고 없음</td>
      </tr>
    </tbody>
  </table>
</main>
</body>
</html>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "feat(wms): 공통 레이아웃 fragment·admin.css 도입, 재고 화면에 예약·가용 컬럼"
```

---

### Task 3: 대시보드 — `GET /`

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java` (findAllReservations 추가)
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java` (dashboard 핸들러 추가)
- Create: `src/main/resources/templates/admin/dashboard.html`
- Modify: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `InventoryRowResponse` 4필드, Task 2 fragment.
- Produces: `InventoryService.findAllReservations()` → `List<Reservation>` (id 역순) — Task 4 예약 화면도 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`WmsAdminControllerTest`에 추가 (import에 `com.jhg.wms.domain.*` 필요):

```java
@Test
void 대시보드_재고_발주_예약_요약을_모델에_담는다() throws Exception {
    when(inventoryService.findAllRows()).thenReturn(List.of(
            new InventoryRowResponse(1L, 10, 3, 7),
            new InventoryRowResponse(2L, 5, 0, 5)));
    when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(
            PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10))));
    Reservation shipped = Reservation.reserve(2L);
    shipped.ship();
    when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(1L), shipped));

    mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/dashboard"))
            .andExpect(model().attribute("skuCount", 2))
            .andExpect(model().attribute("totalOnHand", 15))
            .andExpect(model().attribute("totalReserved", 3))
            .andExpect(model().attribute("totalAvailable", 12))
            .andExpect(model().attribute("orderedPoCount", 1L))
            .andExpect(model().attribute("reservedCount", 1L))
            .andExpect(model().attribute("shippedCount", 1L))
            .andExpect(model().attribute("releasedCount", 0L));
}
```

`InventoryServiceTest`에 추가:

```java
@Test
void findAllReservations_ID_역순으로_반환한다() {
    reservationRepo.save(com.jhg.wms.domain.Reservation.reserve(1L));
    reservationRepo.save(com.jhg.wms.domain.Reservation.reserve(2L));
    var list = service.findAllReservations();
    assertThat(list).hasSize(2);
    assertThat(list.get(0).getOrderId()).isEqualTo(2L);
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest" --tests "com.jhg.wms.service.InventoryServiceTest"`
Expected: FAIL — `findAllReservations()` 심볼 없음

- [ ] **Step 3: 구현**

`InventoryService`에 추가 (import `org.springframework.data.domain.Sort`):

```java
/** 관리자 예약 화면·대시보드용 전체 예약 목록 (최신 먼저). */
public List<Reservation> findAllReservations() {
    return reservationRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
}
```

`WmsAdminController`에 추가 (import: `com.jhg.wms.domain.*`, `com.jhg.wms.web.InventoryRowResponse`는 동일 패키지, `java.util.Map`, `java.util.stream.Collectors`):

```java
@GetMapping("/")
public String dashboard(Model model) {
    List<InventoryRowResponse> rows = inventoryService.findAllRows();
    model.addAttribute("skuCount", rows.size());
    model.addAttribute("totalOnHand", rows.stream().mapToInt(InventoryRowResponse::onHandQty).sum());
    model.addAttribute("totalReserved", rows.stream().mapToInt(InventoryRowResponse::reservedQty).sum());
    model.addAttribute("totalAvailable", rows.stream().mapToInt(InventoryRowResponse::availableQty).sum());
    model.addAttribute("orderedPoCount", purchaseOrderService.findAllWithItems().stream()
            .filter(po -> po.getStatus() == PurchaseOrderStatus.ORDERED).count());
    Map<ReservationStatus, Long> resCounts = inventoryService.findAllReservations().stream()
            .collect(Collectors.groupingBy(Reservation::getStatus, Collectors.counting()));
    model.addAttribute("reservedCount", resCounts.getOrDefault(ReservationStatus.RESERVED, 0L));
    model.addAttribute("shippedCount", resCounts.getOrDefault(ReservationStatus.SHIPPED, 0L));
    model.addAttribute("releasedCount", resCounts.getOrDefault(ReservationStatus.RELEASED, 0L));
    return "admin/dashboard";
}
```

`templates/admin/dashboard.html` 신규:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 대시보드')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('dashboard')}"></nav>
<main>
  <h2>대시보드</h2>
  <div class="cards">
    <div class="card">
      <h3>재고</h3>
      <p class="big" th:text="${skuCount}">20</p>
      <p class="label">SKU</p>
      <p>보유 <b th:text="${totalOnHand}">0</b> · 예약 <b th:text="${totalReserved}">0</b> · 가용 <b th:text="${totalAvailable}">0</b></p>
      <p><a th:href="@{/admin/inventory}">재고 관리 →</a></p>
    </div>
    <div class="card">
      <h3>발주</h3>
      <p class="big" th:text="${orderedPoCount}">0</p>
      <p class="label">입고대기</p>
      <p><a th:href="@{/admin/purchase-orders(status='ORDERED')}">입고대기 발주 →</a></p>
    </div>
    <div class="card">
      <h3>예약</h3>
      <p>
        <span class="badge badge-reserved">RESERVED</span> <b th:text="${reservedCount}">0</b><br/>
        <span class="badge badge-shipped">SHIPPED</span> <b th:text="${shippedCount}">0</b><br/>
        <span class="badge badge-released">RELEASED</span> <b th:text="${releasedCount}">0</b>
      </p>
      <p><a th:href="@{/admin/reservations}">예약 현황 →</a></p>
    </div>
  </div>
</main>
</body>
</html>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest" --tests "com.jhg.wms.service.InventoryServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "feat(wms): 대시보드(GET /) — 재고·발주·예약 요약 카드, 루트 화이트페이지 해소"
```

---

### Task 4: 예약 현황 화면 — `GET /admin/reservations`

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Create: `src/main/resources/templates/admin/reservations.html`
- Modify: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 3 `InventoryService.findAllReservations()`, Task 2 fragment.

- [ ] **Step 1: 실패하는 테스트 작성**

`WmsAdminControllerTest`에 추가:

```java
@Test
void 예약화면_전체_목록을_렌더링한다() throws Exception {
    when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L)));

    mockMvc.perform(get("/admin/reservations"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/reservations"))
            .andExpect(content().string(containsString("10")))
            .andExpect(content().string(containsString("RESERVED")));
}

@Test
void 예약화면_상태_필터가_동작한다() throws Exception {
    Reservation shipped = Reservation.reserve(20L);
    shipped.ship();
    when(inventoryService.findAllReservations()).thenReturn(List.of(Reservation.reserve(10L), shipped));

    mockMvc.perform(get("/admin/reservations").param("status", "SHIPPED"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reservations", List.of(shipped)));
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: FAIL — `/admin/reservations` 매핑 없음 (404)

- [ ] **Step 3: 구현**

`WmsAdminController`에 추가:

```java
@GetMapping("/admin/reservations")
public String reservations(@RequestParam(required = false) ReservationStatus status, Model model) {
    List<Reservation> reservations = inventoryService.findAllReservations();
    if (status != null)
        reservations = reservations.stream().filter(r -> r.getStatus() == status).toList();
    model.addAttribute("reservations", reservations);
    model.addAttribute("activeStatus", status);
    return "admin/reservations";
}
```

`templates/admin/reservations.html` 신규:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 예약 현황')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('reservations')}"></nav>
<main>
  <h2>예약 현황</h2>
  <p class="label">조회 전용 — 예약·출고·해제는 OMS가 API로 처리합니다.</p>

  <div class="tabs">
    <a th:href="@{/admin/reservations}" th:classappend="${activeStatus == null} ? 'active'">전체</a>
    <a th:href="@{/admin/reservations(status='RESERVED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'RESERVED'} ? 'active'">RESERVED</a>
    <a th:href="@{/admin/reservations(status='SHIPPED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'SHIPPED'} ? 'active'">SHIPPED</a>
    <a th:href="@{/admin/reservations(status='RELEASED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'RELEASED'} ? 'active'">RELEASED</a>
  </div>

  <table>
    <thead><tr><th>예약 ID</th><th>주문 ID</th><th>상태</th></tr></thead>
    <tbody>
      <tr th:each="r : ${reservations}">
        <td th:text="${r.id}">1</td>
        <td th:text="${r.orderId}">1</td>
        <td><span class="badge" th:classappend="'badge-' + ${#strings.toLowerCase(r.status.name())}" th:text="${r.status.name()}">RESERVED</span></td>
      </tr>
      <tr th:if="${#lists.isEmpty(reservations)}">
        <td colspan="3">예약 내역이 없습니다.</td>
      </tr>
    </tbody>
  </table>
</main>
</body>
</html>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "feat(wms): 예약 현황 화면(/admin/reservations) — 상태 필터 탭, 조회 전용"
```

---

### Task 5: 발주 화면 보강 — 상태 필터·입고일시·다품목 폼

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java:40-45` (purchaseOrders 핸들러)
- Modify: `src/main/resources/templates/admin/purchaseorders.html` (전체 교체)
- Modify: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 2 fragment, 기존 `PurchaseOrderService.findAllWithItems()`, `PurchaseOrderForm(items[i].productId, items[i].quantity, memo)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`WmsAdminControllerTest`에 추가:

```java
@Test
void 발주화면_상태_필터가_동작한다() throws Exception {
    PurchaseOrder ordered = PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10));
    PurchaseOrder received = PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 5));
    received.receive();
    when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(ordered, received));
    when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10, 0, 10)));

    mockMvc.perform(get("/admin/purchase-orders").param("status", "RECEIVED"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("purchaseOrders", List.of(received)));
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: FAIL — 필터 미구현이라 purchaseOrders가 2건

- [ ] **Step 3: 구현**

`WmsAdminController.purchaseOrders` 교체:

```java
@GetMapping("/admin/purchase-orders")
public String purchaseOrders(@RequestParam(required = false) PurchaseOrderStatus status, Model model) {
    List<PurchaseOrder> pos = purchaseOrderService.findAllWithItems();
    if (status != null)
        pos = pos.stream().filter(po -> po.getStatus() == status).toList();
    model.addAttribute("purchaseOrders", pos);
    model.addAttribute("activeStatus", status);
    model.addAttribute("products", inventoryService.findAllRows());
    return "admin/purchaseorders";
}
```

`templates/admin/purchaseorders.html` 전체 교체:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('WMS 발주 관리')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('purchase-orders')}"></nav>
<main>
  <h2>발주 관리</h2>
  <div th:replace="~{fragments/layout :: flash}"></div>

  <h3>발주 생성</h3>
  <form th:action="@{/admin/purchase-orders}" method="post">
    <div id="po-lines">
      <div class="form-row po-line">
        <div>
          <label>상품 ID</label><br/>
          <select name="items[0].productId">
            <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
          </select>
        </div>
        <div>
          <label>수량</label><br/>
          <input type="number" name="items[0].quantity" min="1" value="10" style="width:80px" />
        </div>
      </div>
    </div>
    <div class="form-row">
      <button type="button" class="ghost sm" onclick="addLine()">+ 품목 추가</button>
      <button type="button" class="ghost sm" onclick="removeLine()">− 품목 삭제</button>
    </div>
    <div class="form-row">
      <div>
        <label>메모</label><br/>
        <input type="text" name="memo" placeholder="긴급 발주 등" />
      </div>
      <button type="submit">발주 생성</button>
    </div>
  </form>

  <h3>발주 현황</h3>
  <div class="tabs">
    <a th:href="@{/admin/purchase-orders}" th:classappend="${activeStatus == null} ? 'active'">전체</a>
    <a th:href="@{/admin/purchase-orders(status='ORDERED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'ORDERED'} ? 'active'">입고대기</a>
    <a th:href="@{/admin/purchase-orders(status='RECEIVED')}" th:classappend="${activeStatus != null and activeStatus.name() == 'RECEIVED'} ? 'active'">입고완료</a>
  </div>
  <table>
    <thead>
      <tr><th>발주번호</th><th>상태</th><th>품목</th><th>메모</th><th>발주일시</th><th>입고일시</th><th>입고</th></tr>
    </thead>
    <tbody>
      <tr th:each="po : ${purchaseOrders}">
        <td th:text="${po.id}">1</td>
        <td th:text="${po.status.name() == 'RECEIVED'} ? '입고완료' : '입고대기'">입고대기</td>
        <td>
          <span th:each="item, stat : ${po.items}">
            <span th:text="|상품#${item.productId} x${item.quantity}|">상품#1 x10</span><span th:if="${!stat.last}">, </span>
          </span>
        </td>
        <td th:text="${po.memo}">긴급</td>
        <td th:text="${#temporals.format(po.createdAt,'yyyy-MM-dd HH:mm')}">2026-07-01 10:00</td>
        <td th:text="${po.receivedAt != null} ? ${#temporals.format(po.receivedAt,'yyyy-MM-dd HH:mm')} : '—'">—</td>
        <td>
          <form th:if="${po.status.name() != 'RECEIVED'}" th:action="@{/admin/purchase-orders/receive}" method="post" style="margin:0">
            <input type="hidden" name="poId" th:value="${po.id}" />
            <button class="sm" type="submit">입고</button>
          </form>
          <span th:if="${po.status.name() == 'RECEIVED'}">—</span>
        </td>
      </tr>
      <tr th:if="${#lists.isEmpty(purchaseOrders)}">
        <td colspan="7">발주 내역이 없습니다.</td>
      </tr>
    </tbody>
  </table>

  <script>
    function addLine() {
      const lines = document.getElementById('po-lines');
      const line = lines.firstElementChild.cloneNode(true);
      const idx = lines.children.length;
      line.querySelectorAll('select, input').forEach(el => {
        el.name = el.name.replace(/items\[\d+\]/, 'items[' + idx + ']');
      });
      lines.appendChild(line);
    }
    function removeLine() {
      const lines = document.getElementById('po-lines');
      if (lines.children.length > 1) lines.removeChild(lines.lastElementChild);
    }
  </script>
</main>
</body>
</html>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "feat(wms): 발주 화면 보강 — 상태 필터·입고일시·다품목 발주 폼"
```

---

### Task 6: error.html + README 갱신 + 전체 검증

**Files:**
- Create: `src/main/resources/templates/error.html`
- Modify: `README.md:85-90` (관리자 UI 표)
- Test: 없음 — error.html은 서블릿 컨테이너 에러 경로라 MockMvc 슬라이스로 커버 불가, Step 4 수동 스모크로 확인

**Interfaces:**
- Consumes: Task 2 fragment.

- [ ] **Step 1: error.html 작성**

Spring Boot는 `templates/error.html`이 있으면 Whitelabel 대신 사용한다 (관례, 설정 불필요).

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('오류 — JHG-WMS')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav('')}"></nav>
<main>
  <h2>문제가 발생했습니다</h2>
  <p>
    <b th:text="${status} ?: '오류'">404</b>
    <span th:text="${error} ?: ''">Not Found</span>
  </p>
  <p><a th:href="@{/}">← 대시보드로 돌아가기</a></p>
</main>
</body>
</html>
```

- [ ] **Step 2: README 관리자 UI 표 갱신**

기존 표를 다음으로 교체:

```markdown
### 관리자 UI (Thymeleaf)

| URL | 설명 |
|-----|------|
| `/` | 대시보드 — 재고·발주·예약 요약 |
| `/admin/inventory` | 재고 조회(보유·예약·가용)·수동 조정 |
| `/admin/reservations` | 예약 현황 조회 (상태 필터, 조회 전용) |
| `/admin/purchase-orders` | 발주 생성(다품목)·입고 처리 (상태 필터) |
```

- [ ] **Step 3: 전체 테스트**

Run: `./gradlew test`
Expected: 전체 PASS

- [ ] **Step 4: 수동 스모크 (서버 기동 상태에서)**

- `http://localhost:8081/` → 대시보드 카드 3개
- `http://localhost:8081/admin/inventory` → 4컬럼 테이블, 조정 폼 동작
- `http://localhost:8081/admin/reservations` → 탭 필터 동작
- `http://localhost:8081/admin/purchase-orders` → 품목 추가 버튼으로 2품목 발주 생성 → 입고 → 재고 증가 확인
- `http://localhost:8081/없는경로` → error.html (Whitelabel 아님)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(wms): error.html로 Whitelabel 대체, README 관리자 UI 갱신"
```
