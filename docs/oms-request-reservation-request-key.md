# [OMS 작업 요청] 재고 연동 키를 `orderId` → `requestKey`(UUID)로 전환

**요청일** 2026-09-03 · **WMS 쪽 상태** 구현·테스트 완료(434건 통과), `feat/wms-reservation-request-key` 브랜치
**성격** OMS↔WMS 계약 변경 · **호환성** 깨는 변경(아래 "전환 중 통합 중단" 참고)

---

## 1. 한 줄 요약

WMS의 예약 원장이 이제 `orderId`가 아니라 **OMS가 주문 생성 시 발급하는 `requestKey`(UUID)** 로 식별됩니다.
OMS는 주문에 UUID 하나를 추가하고, 재고 예약·출고·해제·송장조회 요청에 그 값을 실어 보내면 됩니다.

## 2. 왜 바꾸나 — 409 불편이 아니라 조용한 오답이었다

OMS DB를 초기화하면 `orderId` 시퀀스가 1부터 다시 발급됩니다. WMS에는 같은 번호의 옛 예약이 남아 있고요.
기존 설계는 이 재사용을 "기존 예약 원장과 요청 품목을 비교해 다르면 409"로 막고 있었습니다.

**문제는 품목·수량까지 같을 때입니다.** 로컬에서 같은 테스트 주문을 재실행하면 흔한 경우입니다.
당시 `InventoryService.reserveAll`은 이랬습니다:

```java
if (existing != null) {
    if (!ledger.equals(qtyByProductId)) throw 409;
    return existing.getStatus() != ReservationStatus.RELEASED;   // SHIPPED면 true
}
```

옛 예약이 `SHIPPED`면 **409가 나지 않고 `true`를 반환**합니다. 그 결과:

1. WMS는 재고를 하나도 잡지 않았는데 **OMS만 예약 성공으로 믿습니다** → 재고 과다확약
2. 이어진 `shipAll`은 이미 `SHIPPED`라 차감 블록을 건너뛰고, `trackingNumber`도 이미 있어 재발급하지 않습니다
   → **신규 주문이 옛 주문의 송장번호를 받습니다**

`requestKey`는 주문마다 새로 만드는 UUID라 세대가 다르면 반드시 다릅니다. 이 경로가 구조적으로 사라집니다.

> **이름에 대하여**: 새 개념이 아닙니다. 이미 `CustomerReturn.requestKey`, `PaymentAttempt.requestKey`(OMS),
> `ReplenishmentRequest.requestKey`, `RmaReturn.requestKey`(WMS)가 같은 역할을 하고 있습니다.
> 예약/출고/해제만 숫자 PK에 남아 있던 마지막 잔여물을 정리하는 것이라 **이름도 `requestKey`로 통일**했습니다.
> (`fulfillmentKey` 같은 새 이름은 동일 개념의 두 번째 이름이 되므로 쓰지 않았습니다.)

## 3. WMS 계약 변경 — 전/후

정본은 WMS `README.md`입니다. 아래는 요약입니다.

| 엔드포인트 | 전 | 후 |
|---|---|---|
| `POST /api/inventory/reserve` | `{"orderId":1,"items":{...}}` | `{"requestKey":"UUID","orderId":1,"items":{...}}` |
| `POST /api/inventory/ship` | `{"orderId":1,"items":{...}}` | `{"requestKey":"UUID","items":{...}}` |
| `POST /api/inventory/release` | `{"orderId":1,"items":{...}}` | `{"requestKey":"UUID","items":{...}}` |
| `GET /api/shipments/{orderId}` | 경로에 숫자 | `GET /api/shipments/{requestKey}` — 경로에 UUID |
| `POST /api/delivery-events` (WMS→OMS 콜백) | `{"orderId":202,"deliveredAt":"..."}` | `{"requestKey":"UUID","orderId":202,"deliveredAt":"..."}` |

**응답 변화** — `ship`·`shipments` 응답에 `requestKey` 필드가 추가되고, 송장번호 포맷이 바뀝니다:

```json
{
  "requestKey": "3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33",
  "orderId": 202,
  "carrierCode": "MOCK",
  "carrierName": "테스트택배",
  "trackingNumber": "MOCK-202-20260827063000-3f2a9c14",
  "issuedAt": "2026-08-27T06:30:00.123456Z"
}
```

- 송장번호 규칙: `MOCK-{orderId}-{yyyyMMddHHmmss}-{requestKey 앞 8자}`.
  뒤 8자가 없으면 재사용된 `orderId`가 같은 초에 출고될 때 같은 문자열이 나와 유니크 제약에 걸립니다.
  **송장번호를 파싱해서 orderId를 뽑고 있다면 확인이 필요합니다.**
- `ship`/`release`는 `orderId`를 더 이상 읽지 않습니다(예약 원장이 SSOT). 보내도 무시되니 빼도 됩니다.
- `requestKey`가 없으면 **400**입니다: `requestKey는 필수입니다.`

**중요 — `orderId`는 여전히 필요합니다.** `reserve`에서 예약 행에 저장하고, 관리자 화면·수불대장
(`ORDER#{orderId}`)·로그가 사람이 읽는 번호로 씁니다. 다만 **키가 아니며 유니크 제약이 없습니다.**

## 4. OMS가 해야 할 일

### 4.1 주문에 `requestKey` 추가

`Order` 엔티티에 `UUID requestKey` 필드를 추가하고 **주문 생성 시점에 `UUID.randomUUID()`로 발급**합니다.
`CustomerReturn`·`PaymentAttempt`가 이미 쓰는 것과 같은 방식이라 새로 도입할 것은 없습니다.

- 컬럼: `NOT NULL`, `UNIQUE`
- 이 값은 **주문 수명 내내 불변**이어야 합니다. 재시도가 같은 키로 가야 멱등이 성립합니다.

### 4.2 포트 시그니처

`contract/InventoryPort.java`:

```java
boolean reserveAll(UUID requestKey, Long orderId, Map<Long, Integer> qtyByProductId);
ShipmentResult shipAll(UUID requestKey, Map<Long, Integer> qtyByProductId);
void releaseAll(UUID requestKey, Map<Long, Integer> qtyByProductId);
```

`contract/InventoryQueryPort.java`:

```java
Optional<ShipmentInfo> shipmentByRequestKey(UUID requestKey);   // 기존 shipmentByOrderId 대체
```

### 4.3 어댑터

`wms/adapter/WmsInventoryAdapter.java`

- `record WriteRequest(UUID requestKey, Long orderId, Map<Long, Integer> items)`
- `reserveAll`/`shipAll`/`releaseAll`이 `requestKey`를 실어 보냅니다.
- `shipAll`의 응답 검증이 현재 `!orderId.equals(result.orderId())`인데,
  **`requestKey` 일치로 바꾸는 것을 권합니다** — 그게 이제 진짜 키입니다.
- 재시도 로직(통신 blip·5xx 1회 재시도)은 그대로 두면 됩니다. 같은 `requestKey`면 멱등입니다.

`wms/adapter/WmsInventoryQueryAdapter.java`

- `/api/shipments/{orderId}` → `/api/shipments/{requestKey}`
- 응답 검증도 `requestKey` 기준으로.

### 4.4 호출자 4곳

| 파일 | 위치 | 할 일 |
|---|---|---|
| `oms/service/AllocationProcessor.java` | `reserveAll(orderId, ...)` | 주문의 `requestKey`를 함께 전달 |
| `oms/service/OrderService.java` | `shipAll(...)` | 주문의 `requestKey` 전달 |
| `oms/service/OrderService.java` | `shipmentByOrderId(orderId)` | `shipmentByRequestKey(order.getRequestKey())` |
| `oms/service/CancellationProcessor.java` | `releaseAll(orderId, ...)` | 주문의 `requestKey` 전달 |

### 4.5 배송완료 콜백 수신

`oms/web/api/DeliveryEventApiController.java`

```java
public record DeliveryEvent(UUID requestKey, Long orderId, Instant deliveredAt) {}
```

- **주문 조회는 `requestKey`로** 하세요. `orderId`는 사람이 읽는 참조로만 쓰고 대조 기준으로 쓰지 마세요.
- 현재 `event.orderId() == null` 검증은 `event.requestKey() == null`로 바꾸는 것이 맞습니다.
- 멱등 성격(이미 DELIVERED면 200)은 그대로입니다.

### 4.6 기존 주문 데이터

기존 `orders` 행에는 `requestKey`가 없습니다. WMS는 같은 이유로 백필 마이그레이션을 만들었습니다
(`docs/wms-reservation-request-key-migration.sql` 참고 — `ddl-auto: update`가 행 있는 테이블에
`NOT NULL` 컬럼을 못 넣는 문제까지 처리합니다).

OMS도 같은 선택지입니다:
- **백필**(임의 UUID) — 과거 주문은 WMS 예약과 다시 이어지지 않습니다. WMS 쪽도 백필 값이 임의라 마찬가지입니다.
- **로컬 데이터 정리 후 새 키 체계로 시작** — 로컬/포트폴리오 환경이면 이쪽이 가장 단순합니다.

어느 쪽이든 **과거 주문의 OMS↔WMS 연결은 복원되지 않습니다.** 그게 이 변경의 요지입니다.

## 5. 전환 중 통합 중단 (중요)

WMS는 `requestKey` 없는 요청을 **400으로 거부**합니다. 폴백 경로를 두지 않았습니다 —
폴백이 곧 버그 경로라 남기면 의미가 없기 때문입니다.

따라서 **WMS 브랜치 병합 시점부터 OMS가 반영을 마칠 때까지 로컬 통합은 끊깁니다.**
불편하시면 병합 순서를 맞추거나, WMS PR을 OMS 준비될 때까지 열어둘 수 있습니다 — 알려주세요.

## 6. 미해결 — 반품(RMA) 경로는 아직 `orderId`입니다

`POST /api/returns`는 여전히 `orderId`로 예약을 찾습니다. 반품 요청에 주문의 `requestKey`가 없기 때문입니다.
`orderId`가 더는 유일하지 않으므로 WMS는 임시로 **가장 최근 예약**을 고르도록 했습니다
(`ReservationRepository.findByOrderIdLatestFirstWithLock`).

**후속 요청(이번 범위 아님)**: `CreateRmaRequest`에 주문의 `requestKey`를 추가해주시면
WMS 쪽 조회가 단건으로 바뀌고 이 레거시 메서드를 삭제할 수 있습니다.
(주의: RMA 자신의 `requestKey`와 이름이 겹치므로 `orderRequestKey` 같은 별도 필드명이 필요합니다.)

## 7. 검증

WMS 쪽은 `ReservationRequestKeyTest` 6건이 회귀를 고정합니다. 핵심 시나리오:

> 주문 352를 예약·출고 → OMS DB 초기화 → **같은 orderId·같은 품목**으로 신규 주문
> → 신규 주문분이 실제로 예약되고(available 7→4) 옛 송장이 아닌 자기 송장을 받는다

OMS 반영 후 통합 확인 순서:

1. WMS 기동(8081), OMS 기동(8080)
2. 주문 생성 → `orders.request_key`가 채워졌는지 확인
3. 예약 성공 → WMS `reservation.request_key`가 같은 값인지 확인
4. 출고 → 송장번호 끝 8자가 `requestKey` 앞 8자와 같은지 확인
5. 배송완료(WMS 관리자) → OMS `Delivery`가 `DELIVERED`로 올라가는지 확인

## 8. 참고

- WMS 브랜치: `feat/wms-reservation-request-key`
- 계약 정본: WMS `README.md` — "예약 멱등성 — 키는 `requestKey`다" 절
- 판단 근거·거부한 대안: WMS `.superpowers/sdd/progress.md` — 2026-09-03 항목
- DB 마이그레이션: `docs/wms-reservation-request-key-migration.sql`
