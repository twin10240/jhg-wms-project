# 출고 송장 발급 구현 계획 (WMS V3.1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS가 출고 시 데모용 MOCK 송장을 발급하고 `POST /api/inventory/ship`이 그 정보를 JSON으로 반환한다.

**Architecture:** 별도 `Shipment` 엔티티 대신 `Reservation`에 송장 필드 3개를 붙인다 — `Reservation`이 이미 주문당 1행이라 "주문 하나당 송장 하나"가 제약 추가 없이 성립한다. 동시 출고는 이미 있는 `findByOrderIdWithLock`으로 직렬화하고, `shipAll`의 조기 `return`을 조건 블록으로 바꿔 "이미 출고됐지만 송장 없는 기존 주문"이 송장 발급 지점에 도달하게 한다.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Data JPA, PostgreSQL 17, JUnit 5 + AssertJ + Mockito, Gradle.

## Global Constraints

- 스펙: `docs/superpowers/specs/v3/2026-08-27-shipment-issuance-design.md`
- 브랜치: `feat/wms-shipment` (이미 생성됨, 스펙 커밋 `a980a2c`)
- 빌드/테스트: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
- 테스트 전 로컬 PostgreSQL 17이 떠 있어야 한다: `brew services start postgresql@17`.
- **새 Gradle 의존성을 추가하지 않는다.**
- 시작 시점 테스트 291건 전부 통과. 각 태스크 종료 시 전체 그린 유지.
- 동시성 테스트는 `productId >= 9000`, `orderId >= 9000`만 쓴다.
- 송장번호 시각과 `issuedAt`은 **둘 다 UTC**다. 로컬 시각을 쓰지 않는다.
- `carrierCode`/`carrierName`은 상수다. 설정 키를 만들지 않는다.
- 커밋 메시지는 한국어, 본문에 "왜"를 적는다. 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `domain/Reservation.java` | 송장 필드 3개 + `issueShipment(Instant)` — 송장번호 생성 규칙의 정본 |
| `web/ShipResponse.java` (신규) | 출고 응답 계약. `carrierCode` → `carrierName` 유도 |
| `service/InventoryService.java` | `shipAll` — 락 획득, 송장 발급 조건, 응답 반환 |
| `web/InventoryController.java` | 반환형 `void` → `ShipResponse` |
| `domain/ReservationTest.java` | 송장번호 형식·UTC·멱등 (스프링 없는 단위 테스트) |
| `service/InventoryServiceTest.java` | 발급·재호출·실패 롤백·기존 주문 보완 |
| `concurrency/InventoryConcurrencyTest.java` | 동시 출고 시 송장 1건 (기존 테스트 확장) |
| `web/InventoryControllerTest.java` | 응답 JSON 형식 |
| `README.md` | API 계약 문서화 |

---

### Task 1: 도메인 — 송장 필드와 발급 규칙

**Files:**
- Modify: `src/main/java/com/jhg/wms/domain/Reservation.java`
- Test: `src/test/java/com/jhg/wms/domain/ReservationTest.java`

**Interfaces:**
- Produces: `Reservation.CARRIER_CODE: String` = `"MOCK"`, `Reservation.CARRIER_NAME: String` = `"테스트택배"`
- Produces: `Reservation.issueShipment(Instant now): void`
- Produces: `Reservation.getTrackingNumber(): String`, `getCarrierCode(): String`, `getIssuedAt(): Instant` (Lombok `@Getter`)

- [ ] **Step 1: 실패 테스트를 쓴다**

`src/test/java/com/jhg/wms/domain/ReservationTest.java`의 마지막 `}` 앞에 추가한다.
파일 상단 import에 `java.time.Instant`와 `java.time.ZoneOffset`을 더한다.

```java
    @Test
    void issueShipment_송장번호는_MOCK과_주문번호와_UTC_시각으로_만든다() {
        Reservation r = Reservation.reserve(202L, Map.of(3L, 1));

        // 2026-08-27T06:30:00Z — KST로는 15:30이다. 로컬 시각이 새어 들어오면 이 단언이 깨진다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00Z"));

        assertThat(r.getTrackingNumber()).isEqualTo("MOCK-202-20260827063000");
        assertThat(r.getCarrierCode()).isEqualTo("MOCK");
        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00Z"));
    }

    @Test
    void 예약_직후에는_송장이_없다() {
        Reservation r = Reservation.reserve(202L, Map.of(3L, 1));

        assertThat(r.getTrackingNumber()).isNull();
        assertThat(r.getCarrierCode()).isNull();
        assertThat(r.getIssuedAt()).isNull();
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*ReservationTest*'
```

Expected: 컴파일 실패 — `issueShipment`, `getTrackingNumber` 등이 없다.

- [ ] **Step 3: 필드와 발급 메서드를 추가한다**

`Reservation.java`의 import에 추가한다:

```java
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
```

`qtyByProductId` 필드 선언 아래, `reserve` 팩토리 위에 추가한다:

```java
    /** 데모용 송장. 실제 택배사 연동이 아니라 MOCK 하나만 지원한다. */
    public static final String CARRIER_CODE = "MOCK";
    public static final String CARRIER_NAME = "테스트택배";

    // 송장번호의 시각은 issuedAt과 같은 UTC다. 로컬 시각으로 만들면 응답 안에서 두 값이
    // 서로 다른 숫자를 보여준다(KST 15:30 vs UTC 06:30Z).
    private static final DateTimeFormatter TRACKING_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** 송장번호. 주문당 1회만 발급되므로 unique. 출고 전·해제된 예약은 null이 정상이다. */
    @Column(unique = true)
    private String trackingNumber;

    @Column
    private String carrierCode;

    /** 서버 시간대에 따라 해석이 갈리면 안 되는 값이라 Instant다(서비스 경계를 넘는다). */
    @Column
    private Instant issuedAt;
```

`release()` 아래에 추가한다:

```java
    /**
     * 출고 송장 발급. 주문당 1회이며 재발급하지 않는다 — 호출 측이 trackingNumber == null을 확인하고 부른다.
     * 여기서 다시 검사하지 않는 이유: 발급 여부 판단은 락을 쥔 서비스의 책임이고,
     * 도메인이 조용히 no-op 하면 호출 측 버그가 숨는다.
     */
    public void issueShipment(Instant now) {
        this.carrierCode = CARRIER_CODE;
        this.issuedAt = now;
        this.trackingNumber = CARRIER_CODE + "-" + orderId + "-" + TRACKING_TS.format(now);
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*ReservationTest*'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 전체 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 293건 통과(291 + 2). 스키마는 `ddl-auto`가 새 열 3개를 자동 추가한다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/Reservation.java \
        src/test/java/com/jhg/wms/domain/ReservationTest.java
git commit -m "$(cat <<'EOF'
feat(wms): Reservation에 출고 송장 필드와 발급 규칙 추가

별도 Shipment 엔티티를 만들지 않는다. Reservation이 이미 주문당 1행이라
"주문 하나당 송장 하나"가 제약 추가 없이 구조적으로 성립한다.

송장번호의 시각은 issuedAt과 같은 UTC를 쓴다. 로컬 시각으로 만들면 응답 안에서
두 값이 서로 다른 숫자를 보여준다(KST 15:30 vs UTC 06:30Z). 송장번호는 불투명한
표시용 문자열이라 UTC로 맞춰도 잃는 게 없다.

carrierName은 저장하지 않는다 — 이름이 바뀌면 과거 행과 현재 코드가 어긋난다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 출고 흐름과 응답 계약

**Files:**
- Create: `src/main/java/com/jhg/wms/web/ShipResponse.java`
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java` (`shipAll`)
- Modify: `src/main/java/com/jhg/wms/web/InventoryController.java` (`ship`)
- Test: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java`
- Test: `src/test/java/com/jhg/wms/web/InventoryControllerTest.java`

**Interfaces:**
- Consumes: `Reservation.issueShipment(Instant)`, `Reservation.CARRIER_NAME` (Task 1)
- Consumes: `ReservationRepository.findByOrderIdWithLock(Long): Optional<Reservation>` (기존)
- Produces: `ShipResponse(Long orderId, String carrierCode, String carrierName, String trackingNumber, Instant issuedAt)`
- Produces: `ShipResponse.from(Reservation): ShipResponse`
- Produces: `InventoryService.shipAll(Long, Map<Long,Integer>): ShipResponse` (반환형 변경, 기존 `void`)

- [ ] **Step 1: 응답 레코드를 만든다**

이 단계는 테스트보다 먼저 한다 — 테스트가 이 타입을 참조해야 컴파일된다.

`src/main/java/com/jhg/wms/web/ShipResponse.java` 생성:

```java
package com.jhg.wms.web;

import com.jhg.wms.domain.Reservation;

import java.time.Instant;

/**
 * 출고 응답. OMS가 송장 정보를 저장·표시한다.
 *
 * <p>status 필드는 두지 않는다 — 성공 응답은 항상 SHIPPED이고 나머지는 HTTP 오류라 중복이다.
 *
 * <p><b>trackingNumber를 키로 쓰지 말 것.</b> 발급 시각이 들어가므로 WMS DB가 초기화되면
 * 같은 주문이 다른 번호를 받는다. 상관관계는 orderId로 잡는다.
 */
public record ShipResponse(Long orderId, String carrierCode, String carrierName,
                           String trackingNumber, Instant issuedAt) {

    public static ShipResponse from(Reservation r) {
        // 택배사가 MOCK 하나뿐이라 이름을 상수에서 유도한다. 늘어나면 code→name 매핑이 필요하다.
        return new ShipResponse(r.getOrderId(), r.getCarrierCode(), Reservation.CARRIER_NAME,
                r.getTrackingNumber(), r.getIssuedAt());
    }
}
```

- [ ] **Step 2: 실패 테스트 4건을 쓴다**

`src/test/java/com/jhg/wms/service/InventoryServiceTest.java`의 마지막 `}` 앞에 추가한다.
파일 상단 import에 `com.jhg.wms.web.ShipResponse`를 더한다.

```java
    @Test
    void shipAll_출고하면_MOCK_송장을_발급한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));

        ShipResponse res = service.shipAll(99L, Map.of(1L, 6));

        assertThat(res.orderId()).isEqualTo(99L);
        assertThat(res.carrierCode()).isEqualTo("MOCK");
        assertThat(res.carrierName()).isEqualTo("테스트택배");
        assertThat(res.trackingNumber()).matches("MOCK-99-\\d{14}");
        assertThat(res.issuedAt()).isNotNull();
    }

    @Test
    void shipAll_재호출해도_같은_송장을_주고_재고는_한_번만_줄어든다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));

        ShipResponse first = service.shipAll(99L, Map.of(1L, 6));
        ShipResponse again = service.shipAll(99L, Map.of(1L, 6));

        assertThat(again.trackingNumber()).isEqualTo(first.trackingNumber());
        assertThat(again.issuedAt()).isEqualTo(first.issuedAt());
        assertThat(repo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(4);
    }

    @Test
    void shipAll_재고행이_없어_실패하면_송장도_생기지_않는다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        // 예약 후 재고 행이 사라진 상태 — 출고 루프가 예외를 던지고 송장 발급에 도달하지 못한다.
        repo.delete(repo.findByProductId(1L).orElseThrow());
        em.flush();

        assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, 6)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservationRepo.findByOrderId(99L).orElseThrow().getTrackingNumber()).isNull();
    }

    @Test
    void shipAll_이미_출고됐지만_송장이_없으면_송장만_보완한다() {
        seed(1L, 10);
        service.reserveAll(99L, Map.of(1L, 6));
        service.shipAll(99L, Map.of(1L, 6));
        // 이 기능 이전에 출고된 주문 재현 — 송장 세 필드를 모두 비운다.
        em.createQuery("UPDATE Reservation r SET r.trackingNumber = null, r.carrierCode = null, "
                + "r.issuedAt = null WHERE r.orderId = 99").executeUpdate();
        em.clear();

        ShipResponse res = service.shipAll(99L, Map.of(1L, 6));

        assertThat(res.trackingNumber()).matches("MOCK-99-\\d{14}");
        assertThat(repo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(4);   // 재고 불변
    }
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*InventoryServiceTest*'
```

Expected: 컴파일 실패 — `shipAll`이 `void`라 `ShipResponse`에 대입할 수 없다.

- [ ] **Step 4: shipAll을 바꾼다**

`InventoryService.java`의 import에 `java.time.Instant`와 `com.jhg.wms.web.ShipResponse`를 더한다.

메서드 전문을 아래로 교체한다. 재고 차감 루프의 내용은 기존과 같고, `if` 블록 안으로 들어간 것뿐이다.

```java
    /**
     * 예약분 출고 + 송장 발급. 이미 출고됐으면 재고를 다시 깎지 않고, 이미 송장이 있으면 재발급하지 않는다.
     * 해제된 예약은 출고 거부(반쪽 상태 오염 방지).
     * <p>동시 요청은 findByOrderIdWithLock으로 직렬화한다 — 두 번째 요청은 첫 번째가 커밋한 뒤
     * SHIPPED + 송장이 채워진 상태를 보고 같은 값을 반환한다.
     */
    @Transactional
    public ShipResponse shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        // 잠금 조회로 바꾼다 — 두 요청이 동시에 오면 둘 다 SHIPPED 검사를 통과해 송장이 두 장 나온다.
        Reservation reservation = reservationRepository.findByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() == ReservationStatus.RELEASED)
            throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);

        if (reservation.getStatus() != ReservationStatus.SHIPPED) {
            // 호출자 요청 수량이 아니라 예약 원장(SSOT)을 재생한다 — 수량 오염·누락행 침묵 스킵 차단.
            // ship()은 onHand·reserved를 동시에 깎아 applyDelta(onHand 전용)를 못 쓰므로 전용 루프로 SHIP을 기록한다.
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
                    pid, InventoryTransactionType.SHIP, -qty, before, inv.getOnHandQty(),
                    "ORDER#" + orderId, null, actorProvider.current()));
            });
            reservation.ship();
        }
        // 조기 return을 없앤 이유: 이미 출고됐지만 송장이 없는 기존 주문이 여기 도달해야 한다.
        if (reservation.getTrackingNumber() == null)
            reservation.issueShipment(Instant.now());

        return ShipResponse.from(reservation);
    }
```

- [ ] **Step 5: 컨트롤러 반환형을 바꾼다**

`InventoryController.java`:

```java
    @PostMapping("/ship")
    public ShipResponse ship(@RequestBody InventoryWriteRequest req) {
        return inventoryService.shipAll(req.orderId(), req.items());
    }
```

- [ ] **Step 6: 컨트롤러 테스트를 응답 형식까지 보게 고친다**

`InventoryControllerTest.java`의 `ship_서비스에_위임한다`를 교체한다.
파일 상단 import에 `com.jhg.wms.web.ShipResponse`와 `java.time.Instant`를 더한다
(같은 패키지라 `ShipResponse`는 import 없이도 되지만, `Instant`는 필요하다).

```java
    @Test
    void ship_서비스에_위임하고_송장을_JSON으로_반환한다() throws Exception {
        when(inventoryService.shipAll(eq(1L), any())).thenReturn(new ShipResponse(
                1L, "MOCK", "테스트택배", "MOCK-1-20260827063000",
                Instant.parse("2026-08-27T06:30:00Z")));

        mockMvc.perform(post("/api/inventory/ship").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.carrierCode").value("MOCK"))
                .andExpect(jsonPath("$.carrierName").value("테스트택배"))
                .andExpect(jsonPath("$.trackingNumber").value("MOCK-1-20260827063000"))
                .andExpect(jsonPath("$.issuedAt").value("2026-08-27T06:30:00Z"))
                // status는 계약에서 뺐다 — 성공이면 항상 SHIPPED라 중복이다.
                .andExpect(jsonPath("$.status").doesNotExist());

        verify(inventoryService).shipAll(eq(1L), any());
    }
```

`ship_상태충돌은_409를_반환한다`의 `doThrow(...).when(inventoryService).shipAll(eq(1L), any());`는
그대로 둔다 — 반환형이 생겨도 `doThrow().when()` 형식은 유효하다.

`jsonPath`는 별도 임포트가 필요 없다 — 이 파일은 28행에서
`org.springframework.test.web.servlet.result.MockMvcResultMatchers.*`를 이미 정적 임포트한다.

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*InventoryServiceTest*' --tests '*InventoryControllerTest*'
```

Expected: `BUILD SUCCESSFUL`.

`shipAll_이미_출고됐지만_송장이_없으면_송장만_보완한다`가 실패하면 벌크 `UPDATE` 뒤 `em.clear()`가
빠졌는지 확인한다 — 영속성 컨텍스트에 남은 옛 엔티티를 다시 읽으면 송장이 여전히 채워져 있다.

- [ ] **Step 8: 전체 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 297건 통과(293 + 4).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/ShipResponse.java \
        src/main/java/com/jhg/wms/service/InventoryService.java \
        src/main/java/com/jhg/wms/web/InventoryController.java \
        src/test/java/com/jhg/wms/service/InventoryServiceTest.java \
        src/test/java/com/jhg/wms/web/InventoryControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 출고 시 송장을 발급하고 응답으로 반환

OMS가 송장을 저장·표시할 수 있도록 출고 API가 빈 본문 대신 송장 정보를 준다.

조기 return을 조건 블록으로 바꿨다. 이미 출고됐으면 그대로 반환하던 자리인데,
그러면 "이미 출고됐지만 송장이 없는 기존 주문"이 송장 발급 지점에 도달하지 못한다.
재고 재차감은 여전히 status 가드가 막고, 송장 재발급은 trackingNumber 가드가 막는다.

findByOrderId를 잠금 버전으로 바꿨다. 두 요청이 동시에 오면 둘 다 SHIPPED 검사를
통과해 송장이 두 장 나온다. 유니크 제약은 최후 방어선으로 두되, 정상 경로에서
예외를 제어 흐름으로 쓰지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 동시 출고에도 송장이 하나임을 증명한다

**Files:**
- Modify: `src/test/java/com/jhg/wms/concurrency/InventoryConcurrencyTest.java`

**Interfaces:**
- Consumes: `InventoryService.shipAll(Long, Map): ShipResponse` (Task 2)
- Consumes: `ConcurrencySupport.race(int, IntPredicate): RaceResult`, `seedInventory(long, int)`, `onHandOf(long)`, `reservedOf(long)`, `tx`, `em` (기존)

새 테스트 클래스를 만들지 않는다. `같은_예약을_두_스레드가_동시에_출고해도_이중_차감되지_않는다`가
이미 정확히 같은 경합을 재현한다 — 두 번 재현할 이유가 없고, 한 판에 불변식을 더 얹는 쪽이 강하다.

- [ ] **Step 1: 기존 테스트를 확장한다**

`InventoryConcurrencyTest.java`의 import에 추가한다:

```java
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
```

`같은_예약을_두_스레드가_동시에_출고해도_이중_차감되지_않는다` 전체를 교체한다:

```java
    @Test
    void 같은_예약을_두_스레드가_동시에_출고해도_이중차감되지_않고_송장은_하나만_발급된다() {
        long pid = PID_BASE + 3;
        long orderId = ORDER_BASE + 20;
        seedInventory(pid, 10);
        inventoryService.reserveAll(orderId, Map.of(pid, 4));

        // 두 스레드가 받은 송장번호를 모은다. 잠금이 없으면 서로 다른 두 장이 나온다.
        Set<String> trackingNumbers = ConcurrentHashMap.newKeySet();
        RaceResult result = race(2, i -> {
            trackingNumbers.add(inventoryService.shipAll(orderId, Map.of(pid, 4)).trackingNumber());
            return true;
        });

        // 출고는 멱등이므로 둘 다 성공해야 한다. 한 쪽이 예외로 튕기면 OMS 재시도가 실패한다.
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(trackingNumbers)
                .as("송장이 두 장 발급됐다 — 잠금이 없거나 발급 가드를 통과했다")
                .hasSize(1);
        assertThat(onHandOf(pid)).isEqualTo(6);    // 10 - 4, 한 번만 차감
        assertThat(reservedOf(pid)).isZero();
        assertThat(shipRowCountOf(pid))
                .as("SHIP 원장이 두 번 기록됐다 — 재고 차감이 두 번 일어났다는 뜻")
                .isEqualTo(1);
    }

    /** 상품의 SHIP 원장 행 수. 재고 수치는 맞는데 원장만 두 줄인 상태를 잡는다. */
    private long shipRowCountOf(long productId) {
        return tx.execute(s -> em.createQuery(
                        "SELECT COUNT(t) FROM InventoryTransaction t WHERE t.productId = :pid "
                                + "AND t.type = com.jhg.wms.domain.InventoryTransactionType.SHIP", Long.class)
                .setParameter("pid", productId)
                .getSingleResult());
    }
```

- [ ] **Step 2: 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*InventoryConcurrencyTest*'
```

Expected: `BUILD SUCCESSFUL`.

실패하면 Task 2의 `findByOrderIdWithLock` 교체가 빠졌는지 먼저 확인한다.

- [ ] **Step 3: 5회 연속 돌려 플레이키를 확인한다**

```bash
for i in 1 2 3 4 5; do
  JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
    ./gradlew test --tests '*Concurrency*' --rerun-tasks 2>&1 | grep -E 'BUILD (SUCCESSFUL|FAILED)'
done
```

Expected: `BUILD SUCCESSFUL` 5회.

- [ ] **Step 4: 전체 테스트 후 커밋**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 297건 통과(기존 테스트를 교체했으므로 수는 늘지 않는다).

```bash
git add src/test/java/com/jhg/wms/concurrency/InventoryConcurrencyTest.java
git commit -m "$(cat <<'EOF'
test(wms): 동시 출고에도 송장이 한 장뿐임을 증명

기존 이중차감 테스트를 확장했다. 정확히 같은 경합이라 새 클래스를 만들 이유가
없고, 한 판에 불변식을 더 얹는 쪽이 강하다.

두 스레드가 받은 송장번호를 모아 하나인지 본다. 잠금이 없으면 둘 다 SHIPPED
검사를 통과해 서로 다른 두 장이 나온다.

SHIP 원장 행 수도 함께 단언한다. 재고 수치만 보면 두 번 깎였다가 우연히 맞는
경우를 놓친다 — 원장은 그 사실을 남긴다.

성공 건수를 2로 고정한 이유: 출고는 멱등이라 둘 다 성공해야 한다. 한 쪽이
예외로 튕기면 OMS 재시도가 실패한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: API 계약 문서화

**Files:**
- Modify: `README.md` (재고 쓰기 API 표 아래)

**Interfaces:**
- Consumes: Task 2의 `ShipResponse` 필드 이름과 타입

- [ ] **Step 1: README에 출고 응답 절을 추가한다**

`README.md`에서 재고 쓰기 API 표(`| POST | /api/inventory/release | ...` 행)를 찾아,
그 표 바로 아래에 추가한다:

```markdown
#### 출고 응답 — 송장 (V3.1)

출고가 성공하면 WMS가 데모용 송장을 발급하고 다음을 반환합니다. 현재 `MOCK` 택배사만 지원합니다.

```json
{
  "orderId": 202,
  "carrierCode": "MOCK",
  "carrierName": "테스트택배",
  "trackingNumber": "MOCK-202-20260827063000",
  "issuedAt": "2026-08-27T06:30:00Z"
}
```

- **송장번호 규칙**: `MOCK-{orderId}-{yyyyMMddHHmmss}`. 시각은 **UTC**이며 `issuedAt`과 같은 순간입니다.
- **주문당 1건**: `Reservation`이 주문당 1행이라 송장도 하나뿐입니다. 재호출해도 재고를 다시 깎지 않고
  송장도 재발급하지 않으며, **최초에 발급한 같은 송장을 반환**합니다.
- **이 기능 이전에 출고된 주문**은 송장이 없습니다. 재호출하면 재고는 그대로 두고 송장만 발급합니다.
- **`trackingNumber`를 키로 쓰지 마세요.** 발급 시각이 들어가므로 WMS DB가 초기화되면 같은 주문이
  다른 번호를 받습니다. **상관관계는 `orderId`로** 잡고 `trackingNumber`는 표시용으로 저장하세요.
- `status` 필드는 없습니다 — 성공 응답은 항상 `SHIPPED`이고 나머지는 HTTP 오류입니다.

> **요청 `items`에 대한 주의**: `items`는 **형식 검증만** 거칩니다(비어있지 않음, 수량 1 이상).
> **예약 원장과의 일치 여부는 검사하지 않습니다.** 실제 출고 수량은 예약 원장(SSOT)에서 재생되므로,
> 원장과 다른 수량을 보내도 거부되지 않고 무시됩니다.
> (`reserveAll`은 반대로 원장을 대조하고 불일치 시 409를 냅니다 — `ship`만 하지 않습니다.)
```

- [ ] **Step 2: 전체 테스트 후 커밋**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 297건 통과.

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs(wms): 출고 송장 API 계약 문서화

trackingNumber를 키로 쓰지 말라는 경고를 명시했다. 2026-08-27 rmaId 충돌과
같은 구조다 — 로컬 식별자를 전역 키로 쓰면 DB 초기화 후 재현된다.

items가 원장 대조를 하지 않는다는 사실도 적었다. OMS 요청서는 "원장 일치
검증용"으로 이해하고 있었는데 실제로는 형식만 본다. 없는 대조에 기대면 안 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## 최종 확인

- [ ] `./gradlew test` — 297건 통과, 실패 0
- [ ] `dropdb wms_test && createdb -O wms wms_test` 후 재실행 — 깨끗한 DB에서도 통과
- [ ] 동시성 테스트 5회 연속 통과 (플레이키 없음)
- [ ] `grep -n "status" src/main/java/com/jhg/wms/web/ShipResponse.java` — 결과 없음(계약에서 뺐다)
- [ ] PR 생성 후 CI 초록 확인. **CI 시간이 3분을 넘으면 병합 전에 보고한다.**
- [ ] **배포 순서**: WMS 먼저 배포 → 기존 OMS 출고 호출이 여전히 정상인지 확인 → OMS가 응답 파싱·송장 저장 배포
