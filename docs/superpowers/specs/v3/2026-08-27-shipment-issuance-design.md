# 출고 송장 발급 설계 (WMS V3.1)

**목표:** WMS가 출고 시 데모용 송장을 발급하고 출고 API가 그 정보를 반환한다. OMS는 이를 저장·표시한다.

**요청 출처:** OMS "WMS 출고 시 데모용 송장 발급 및 조회 정보 반환 요청" (2026-08-27).

## 배경

OMS의 출고 처리는 `POST /api/inventory/ship`으로 재고와 예약을 차감하지만 송장번호를 관리하지 않는다.
실물 출고와 송장 발급을 WMS 책임으로 두고, OMS는 WMS가 발급한 송장 정보를 저장·표시한다.

**1차 데모용이다.** 실제 택배사 연동이 아니라 `MOCK` 택배사 하나만 지원한다.

## 범위 밖

실제 택배사 API 연동, 수취인 주소 전송, 송장 PDF·라벨 출력, 배송조회 자동 갱신,
송장 취소·재발급, WMS→OMS 별도 콜백, **WMS 관리자 화면 노출**.

관리자 화면을 넣지 않는 이유: 발급도 조회도 OMS가 한다. WMS 운영자가 송장을 쓸 일이 없다.
필요해지면 그때 `/admin/reservations`에 열 하나를 추가하면 된다.

## 이미 충족된 요구

구현 전에 확인한 사실이다. 이 전제 위에서 설계한다.

- **재고 재차감 방지**: `InventoryService.shipAll`에 `if (status == SHIPPED) return;`이 이미 있다.
- **트랜잭션 일체성**: `shipAll`에 `@Transactional`이 있다. 송장 저장을 이 메서드 안에서 하면
  "재고 출고 실패 시 송장 생성 금지 / 송장 저장 실패 시 재고 출고 롤백"이 자동으로 만족된다.
- **주문당 1건**: `Reservation.orderId`가 unique다.
- **비관적 락 메서드**: `ReservationRepository.findByOrderIdWithLock`이 이미 있다(현재 `RmaService`만 사용).

## 설계 결정

### 1. 송장을 `Reservation`에 붙인다 (별도 엔티티 아님)

`Reservation`은 이미 주문당 1행이고 주문의 이행 기록(`RESERVED`→`SHIPPED`→`RELEASED`)이다.
출고 시점 정보가 여기 붙는 것이 자연스럽고, "주문 하나당 송장 하나"가 **제약을 추가하지 않아도
구조적으로 보장**된다. 새 테이블·엔티티·저장소가 0개다.

**대가**: `Reservation`에 배송 관심사가 섞이고, 출고 전·해제된 예약에는 세 열이 `null`로 남는다.
분할 배송·재발급·이력이 생기면 별도 `Shipment` 엔티티로 갈라야 한다 — 그 셋은 모두 범위 밖이라
지금 그 확장성에 값을 치르지 않는다. **배송 관련 필드가 더 늘어나면 분리 신호로 본다.**

### 2. 필드

```java
@Column(unique = true)          // 송장번호 중복 방지
private String trackingNumber;
@Column private String carrierCode;
@Column private Instant issuedAt;
```

셋 다 nullable이다. 출고 전(`RESERVED`)·해제된 예약, 그리고 이 기능 이전에 출고된 기존 주문은
`null`로 남는 것이 정상이다.

**`carrierName`은 저장하지 않는다.** `MOCK` → `테스트택배` 매핑은 코드 상수다. DB에 넣으면
이름이 바뀔 때 과거 행과 현재 코드가 어긋난다. 응답 조립 시 `carrierCode`로 유도한다.

**`carrierCode`/`carrierName`은 설정값이 아니라 상수다.** 값이 변하지 않는데 설정 키를 만들 이유가
없고, 실제 택배사가 붙을 때 필요한 것은 설정 한 줄이 아니라 연동 전체다.

### 3. `issuedAt`은 `Instant`

서버 시간대에 따라 해석이 갈리는 값이 서비스 경계를 넘으면 안 된다. 두 앱이 다른 서버에 뜨면
`LocalDateTime`은 같은 문자열이 다른 순간을 뜻하게 된다.

**대가**: WMS의 다른 시각 필드(`InventoryTransaction.occurredAt`, `RmaReturn.requestedAt` 등)는
전부 `LocalDateTime`이라 코드베이스에 혼재가 생긴다. `Reservation`에는 시각 필드가 하나도 없어
엔티티 내부 불일치는 없고, **서비스 경계를 넘는 값**이라는 이유가 명확하므로 감수한다.

### 4. `trackingNumber` 생성 규칙

```
MOCK-{orderId}-{yyyyMMddHHmmss}      // 시각은 UTC
예) MOCK-202-20260827063000
```

**시각은 `issuedAt`과 같은 UTC를 쓴다.** 송장번호를 로컬 시각으로 만들면 응답 안에서 두 값이
서로 다른 숫자를 보여준다(KST 15:30 vs UTC 06:30Z). 송장번호는 불투명한 표시용 문자열이라
UTC로 맞춰도 잃는 것이 없고, 지원 문의 시 두 값을 대조하기 쉽다.

`orderId`가 들어가고 주문당 1회만 발급되므로 초 단위 정밀도로 충분하다.
`unique` 제약은 최후 방어선으로만 둔다.

## 출고 흐름

```java
@Transactional
public ShipResponse shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
    validateWriteRequest(orderId, qtyByProductId);
    // findByOrderId → findByOrderIdWithLock: 동시 출고를 직렬화한다.
    Reservation r = reservationRepository.findByOrderIdWithLock(orderId)
            .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
    if (r.getStatus() == ReservationStatus.RELEASED)
        throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);

    if (r.getStatus() != ReservationStatus.SHIPPED) {
        ... 기존 재고 차감 루프 (변경 없음) ...
        r.ship();
    }
    // 조기 return을 없앤 이유: 이미 출고됐지만 송장이 없는 기존 주문이 여기 도달해야 한다.
    if (r.getTrackingNumber() == null)
        r.issueShipment(Instant.now());

    return ShipResponse.from(r);
}
```

세 요구가 한 구조로 동시에 만족된다.

| 요구 | 만족 방식 |
|---|---|
| 재고 재차감 없음 | `status != SHIPPED` 가드 (기존 동작 유지) |
| 송장 재발급 없음 | `trackingNumber == null` 가드 |
| 기존 출고 주문 송장 보완 | 조기 `return` 제거로 두 번째 가드에 도달 |

**동시 요청**: `findByOrderIdWithLock`이 행을 잠근다. 두 번째 스레드는 첫 번째가 커밋할 때까지
대기했다가 `SHIPPED` + 송장이 채워진 상태를 보고 같은 값을 반환한다.
**정상 경로에서 예외를 제어 흐름으로 쓰지 않는다** — 유니크 제약 위반을 잡아 재조회하는 방식은
V3.0에서 실사 겹침을 비관적 락으로 고친 방향과 반대라 쓰지 않는다.

## API 계약

### 요청 (변경 없음)

```
POST /api/inventory/ship
{"orderId": 202, "items": {"3": 1}}
```

**`items`는 형식 검증만 거친다** — 비어있지 않음, `productId` non-null, 수량 1 이상.
**예약 원장과의 일치 여부는 검사하지 않는다.** 실제 출고 수량은 예약 원장(SSOT)에서 재생되므로,
원장과 다른 수량을 보내도 **거부되지 않고 무시된다**.

> 비대칭 주의: `reserveAll`은 반대로 원장 대조를 하고 불일치 시 409를 낸다. `ship`만 하지 않는다.
> 이 비대칭이 옳은지는 별개 논의이며 이번 범위 밖이다. 사실만 문서화한다.

### 응답 (변경)

`void`(빈 본문) → JSON.

```java
public record ShipResponse(Long orderId, String carrierCode, String carrierName,
                           String trackingNumber, Instant issuedAt) {}
```

`ShipResponse`는 `web` 패키지에 두고 서비스가 반환한다. `InventoryService.findAllRows`가
`web.InventoryRowResponse`를 반환하는 기존 패턴과 같다 — 새 관례를 만들지 않는다.

```json
{
  "orderId": 202,
  "carrierCode": "MOCK",
  "carrierName": "테스트택배",
  "trackingNumber": "MOCK-202-20260827063000",
  "issuedAt": "2026-08-27T06:30:00Z"
}
```

**`status` 필드는 넣지 않는다.** 성공 응답은 항상 `SHIPPED`이고 나머지는 HTTP 오류이므로
정보가 중복된다.

### 에러 (변경 없음)

| 코드 | 조건 |
|---|---|
| `400` | 형식 검증 실패 — `orderId` 누락, 품목 없음, 수량 0 이하 |
| `409` | 예약 없음, 해제된 예약 출고 시도 |

### OMS가 지켜야 할 계약

**`trackingNumber`를 키로 쓰지 않는다.** 발급 시각이 들어가므로 WMS DB가 초기화되면 같은 주문이
다른 번호를 받는다. **매칭은 `orderId`로 하고 `trackingNumber`는 표시용으로 저장한다.**
2026-08-27 `rmaId` 충돌 사고와 같은 구조다 — 로컬 식별자를 전역 키로 쓰면 재현된다.

## 배포 순서

**WMS 먼저.** OMS의 현재 출고 호출은 `.retrieve().toBodilessEntity()`로 본문을 파싱하지 않고
커넥션을 닫으므로, WMS가 JSON을 반환해도 기존 OMS는 깨지지 않는다.

```
WMS JSON 응답 배포 → 기존 OMS 출고 호출 정상 여부 확인 → OMS 응답 파싱·송장 저장 배포
```

OMS가 의존하기 전에 실제 응답을 보고 확인할 수 있어 이 순서가 안전하다.

## 테스트

| # | 테스트 | 위치 | 단언 |
|---|---|---|---|
| ① | 정상 출고 시 송장 발급 | `InventoryServiceTest` | `MOCK-{orderId}-` 접두 + 14자리 숫자, `issuedAt` non-null |
| ② | 재호출 시 동일 송장 | `InventoryServiceTest` | `trackingNumber`·`issuedAt` 동일, 재고 1회만 차감 |
| ③ | **동시 요청 시 송장 하나** | `InventoryConcurrencyTest` (기존 확장) | 아래 |
| ④ | 출고 실패 시 송장 미생성 | `InventoryServiceTest` | 재고 행 없는 상품 → 예외 → 롤백 후 `trackingNumber == null` |
| ⑤ | 기존 출고 주문 송장 보완 | `InventoryServiceTest` | 송장만 생김, 재고 불변 |

**③이 이 스펙의 핵심 증명이다.** `@SpringBootTest` + `ConcurrencySupport.race()`로 스레드 2개가
같은 `orderId`로 동시에 출고한다. 단언은 타이밍이 아니라 불변 조건이다.

새 테스트 클래스를 만들지 않고 기존 `같은_예약을_두_스레드가_동시에_출고해도_이중_차감되지_않는다`를
확장한다 — 정확히 같은 경합이라 두 번 재현할 이유가 없고, 한 판에 불변식을 더 얹는 쪽이 강하다.

- 발급된 서로 다른 송장번호가 **정확히 1개**
- 모든 성공 응답의 `trackingNumber`가 **동일**
- `SHIP` 원장 행이 상품당 **1건**

V3.0 제약을 따른다 — `productId >= 9000`, `orderId >= 9000`, **새 Gradle 의존성 없음**.

**⑤의 상태를 만드는 방법**: 정상 출고 후 송장 세 필드(`trackingNumber`·`carrierCode`·`issuedAt`)를
모두 `null`로 되돌린 뒤 재호출한다. 이 기능 이전에 출고된 기존 주문을 재현하는 것이다.
가드는 `trackingNumber`만 보지만, 셋 다 비워야 실제 기존 주문과 같은 상태가 된다.

## 문서화

`README.md`의 재고 쓰기 API 절에 추가한다.

- 출고 API 응답 형식과 예시
- `trackingNumber` 생성 규칙과 **UTC 기준**
- 주문별 송장 멱등성
- 현재 `MOCK` 택배사만 지원
- **`trackingNumber`는 표시용, 매칭은 `orderId`로** (OMS 계약)
- `items`가 형식 검증만 거치며 원장 대조를 하지 않는다는 사실
