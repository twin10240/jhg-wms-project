# 부분 입고 (Phase 2) 설계

작성: 2026-07-22. 로드맵 `docs/wms-business-roadmap.md` Phase 2.
전제: Phase 1(재고 트랜잭션 원장) 완료 — `applyDelta`가 onHand 변경·원장 기록의 단일 지점이고,
발주 입고는 이미 `RECEIVE` 타입으로 원장에 남는다.

## 배경

`PurchaseOrder.receive()`가 인자 없이 전량을 한 번에 `RECEIVED`로 만든다. 현실의 "100개 발주 → 60개 도착"을
표현할 방법이 없어, 60개만 왔어도 100개 입고로 기록하거나 나머지가 올 때까지 아무것도 못 한다.

**완료 기준:** 한 발주를 여러 번에 나눠 입고할 수 있고, 품목별 미도착 잔량이 보인다.

## 범위

**포함:** 품목별 누적 입고량, `PARTIALLY_RECEIVED` 상태, 잔량 표시, 발주 상세/입고 페이지, 기존 데이터 백필.

**제외:** 입고 검수(불량·수량불일치 반려). 로드맵에서 "선택"으로 표시된 항목이며, 불량 판정의 뒷단(반품·폐기)이
Phase 4라 지금 넣으면 불량 수량이 갈 곳이 없다. 부분 입고 위에 나중에 얹는다.

**제외:** 동시성 제어. `PurchaseOrder`에 `@Version`이 없어 두 관리자가 동시에 같은 발주를 입고하면 잔량 검증이
밀릴 수 있다. 현재 `receive()`도 동일하게 보호되지 않으며, 단일 관리자 운영 전제로 그대로 둔다.
필요해지면 `Inventory`와 같이 `@Version`을 붙이는 것이 정답이다.

---

## 1. 데이터 모델

신규 엔티티 없음. 신규 테이블 없음.

### `PurchaseOrderItem`

필드 하나 추가:

```java
private int receivedQty;   // 누적 입고량, 신규 생성 시 0
```

`quantity`(발주량)는 그대로 둔다. 잔량은 저장하지 않고 파생시킨다.

도메인 메서드 — 잔량 계산과 초과 검증은 품목의 책임:

```java
public int remainingQty()          // quantity - receivedQty
public boolean isFullyReceived()   // remainingQty() == 0
void receive(int qty)              // 검증 후 receivedQty += qty (package-private)
```

`receive(int)`의 검증:
- `qty < 0` → `IllegalArgumentException`
- `qty > remainingQty()` → `IllegalArgumentException("상품#3: 잔량 40개를 초과했습니다 (요청 50개)")`
- `qty == 0` → **허용**. "이번에 이 품목은 안 왔음"을 뜻하는 정상 입력이다.

### `PurchaseOrderStatus`

```java
public enum PurchaseOrderStatus { ORDERED, PARTIALLY_RECEIVED, RECEIVED }
```

### 손대지 않는 것

`Inventory`, `InventoryTransaction`, `Reservation`, `ReplenishmentRequest`.

---

## 2. 입고 흐름

### `PurchaseOrder.receive(...)`

```java
public Map<Long, Integer> receive(Map<Long, Integer> qtyByItemId)
```

**입력:** 품목ID(`purchaseOrderItemId`) → 이번 입고량.
**반환:** 실제로 반영된 productId → delta. 0인 항목은 담기지 않는다.

품목을 `productId`가 아니라 `purchaseOrderItemId`로 식별한다. 한 발주에 같은 상품이 두 줄로 들어갈 수 있어
productId로는 어느 줄에 반영할지 모호하다.

동작 순서:

1. `status == RECEIVED` → `IllegalStateException` (기존 중복 입고 방어 유지)
2. 모르는 품목ID가 섞였으면 → `IllegalArgumentException`
3. 입력 수량이 **전부 0이면** → `IllegalArgumentException("입고 수량이 없습니다.")`
   빈 제출로 상태만 바뀌는 것을 막는다. 상태를 바꾸기 전에 걸러낸다.
4. 각 품목에 `item.receive(qty)` — 초과·음수 검증 (1절)
5. 상태 재계산 — 전 품목 `isFullyReceived()`면 `RECEIVED` + `receivedAt = now()`, 아니면 `PARTIALLY_RECEIVED`
6. 반영된 delta를 **productId 기준으로 합산해** 반환

**같은 상품이 여러 줄이면 합산한다.** 원장의 `reference`가 `PO#{id}`로 동일해 행을 나눠도 구분할 수단이 없어
실익이 없고, 합산하면 원장 행 수와 OMS 통지 HTTP 호출이 함께 줄어든다.

`receivedAt`은 **전량 완료 시각**을 뜻한다. 부분 입고 중에는 `null`이며 UI에 "—"로 표시된다.
개별 입고 시각은 Phase 1 원장에 남는다.

### `PurchaseOrderService.receive(...)`

```java
@Transactional
public Long receive(Long poId, Map<Long, Integer> qtyByItemId) {
    PurchaseOrder po = purchaseOrderRepository.findById(poId)
            .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));

    po.receive(qtyByItemId).forEach((productId, delta) ->
            inventoryService.applyDelta(productId, delta, InventoryTransactionType.RECEIVE,
                    "PO#" + poId, null));

    if (po.getStatus() == PurchaseOrderStatus.RECEIVED)
        requestRepository.findByPurchaseOrderId(poId).ifPresent(ReplenishmentRequest::fulfill);

    return po.getId();
}
```

**서비스에는 검증도 필터링도 없다.** 무엇이 실제로 반영됐는지는 도메인만 알고, 서비스는 반환값을 원장에 넘기기만
한다. `qty=0` 필터와 `itemId → productId` 변환이 도메인 안에 있으므로 그 지식이 두 곳으로 흩어지지 않는다.

**`fulfill`은 전량 입고 시에만 호출한다.** `fulfill`의 의미가 "요청 물량을 채웠다"인데, 부분 입고에서 호출하면
60개만 왔는데도 채웠다고 OMS에 알리는 거짓 신호가 된다. 조건 한 줄이며 `ReplenishmentRequest` 도메인은
건드리지 않는다.

**도메인 검증이 원장 기록보다 먼저 끝난다.** 초과 입고가 섞이면 `applyDelta` 호출 전에 예외가 나며 트랜잭션
전체가 롤백되므로, 재고만 늘고 발주는 안 늘어나는 어긋남이 생기지 않는다.

**사용 API 주의:** 발주 입고는 `applyDelta(productId, delta, RECEIVE, reference, reason)`를 쓴다.
`InventoryService.adjust(productId, delta, reason)`는 타입이 `ADJUST`로 고정된 관리자 수동 조정 전용
래퍼라 사용할 수 없다. 타입은 문자열이 아니라 `InventoryTransactionType` enum이다.

---

## 3. 기존 데이터 마이그레이션

`receivedQty`가 `int`라 기존 행의 NULL을 그대로 두면 엔티티를 읽는 순간 실패한다. 백필은 필수다.

**규칙 — 상태에서 유도한다:**

- `status = RECEIVED`인 발주의 품목 → `receivedQty = quantity`
- 그 외(`ORDERED`) → `receivedQty = 0`

기존 `receive()`가 전량 입고밖에 못 했으므로 중간 상태는 존재할 수 없고, 이 유도는 정확하다.

**실행 — Phase 1과 같이 `InitDb`에서 벌크 JPQL 2문:**

```java
// 1) 입고완료 발주 → 발주량만큼 입고된 것으로
UPDATE PurchaseOrderItem i SET i.receivedQty = i.quantity
 WHERE i.receivedQty IS NULL
   AND i.purchaseOrder.id IN (SELECT p.id FROM PurchaseOrder p WHERE p.status = RECEIVED)

// 2) 나머지 → 0
UPDATE PurchaseOrderItem i SET i.receivedQty = 0 WHERE i.receivedQty IS NULL
```

- `WHERE receivedQty IS NULL` 가드로 **멱등** — 두 번째 기동부터 0건 갱신. Phase 1의
  `assignAdjustTypeToLegacy()`와 같은 방식이다.
- **벌크 UPDATE라 엔티티를 하이드레이션하지 않는다** — NULL을 `int`로 읽는 문제를 피해 간다.
- **실행 순서 제약:** 이 두 문장이 `PurchaseOrderItem`을 처음 읽는 코드보다 먼저 돌아야 한다.
  Phase 1의 재고 백필은 발주를 건드리지 않으므로 그 앞에 두면 안전하다.
- `clearAutomatically = true` 필요 — 벌크 UPDATE는 영속성 컨텍스트를 우회하므로, 안 비우면 같은 트랜잭션에서
  이어 읽을 때 옛 값이 나온다.

신규 DB는 영향 없다. 시드가 만드는 발주는 처음부터 `receivedQty`를 갖고 태어난다.

---

## 4. UI

### 신규: 발주 상세/입고 페이지

**`GET /admin/purchase-orders/{poId}`** → `admin/purchaseorderdetail.html`

발주 헤더(번호·상태·메모·발주일시·입고일시)와 품목 테이블:

| 상품 | 발주량 | 입고량 | 잔량 | 이번 입고 |
|---|---|---|---|---|
| 상품#1 | 100 | 60 | 40 | `[입력칸]` |

- 잔량 0인 품목은 입력칸 대신 "완료" 표시
- 상태가 `RECEIVED`면 폼 전체를 감추고 목록 링크만
- **입력칸 기본값은 0.** 잔량을 기본값으로 넣으면 실수로 전량 입고를 눌러버리기 쉽다.

**`POST /admin/purchase-orders/{poId}/receive`**

폼 바인딩은 기존 발주 생성 폼 패턴을 따른다 (`purchaseorders.html:17,23`의 `items[0].productId` 스타일):

```html
<input type="hidden" name="items[0].itemId" />
<input type="number" name="items[0].quantity" min="0" />
```

`ReceiveForm`(itemId·quantity 리스트) → 컨트롤러에서 `Map<Long,Integer>`로 변환 → 서비스 호출.
예외는 기존 핸들러와 동일하게 `IllegalArgumentException | IllegalStateException`을 잡아 플래시 메시지로 돌려준다.

**기존 `POST /admin/purchase-orders/receive`(전량 입고)는 삭제한다.** 부분 입고 도입 후 "수량 없이 전량 입고"
우회 경로가 남아 있으면 안 된다.

### 기존 목록 페이지 (`purchaseorders.html`)

- **상태 표시** — 현재 `RECEIVED`면 '입고완료' 아니면 '입고대기'인 이항 삼항식(`:53`)을 3개 상태 각각 표시로 수정
- **필터 탭에 "부분입고" 추가** (`:42-44`). `status`는 이미 enum 바인딩이라 링크만 추가하면 된다
- **품목 칸에 진행도** — `상품#1 x10` → `상품#1 60/100`
- **입고 버튼** → 상세 페이지 링크로 교체

---

## 5. OMS 영향

**OMS(`jhg-commerce-project`) 코드 수정 없음.** 배포·재시작도 불필요하다. 근거:

- **발주는 OMS 경계를 넘지 않는다.** WMS의 발주 기능은 전부 관리자 MVC이고 REST API가 없다.
  `ReplenishmentRequestDto`도 `purchaseOrderId`만 들고 발주 상태는 넘기지 않는다.
  따라서 `PARTIALLY_RECEIVED`는 OMS에 노출될 통로가 없다.
- **타임아웃/재촉 로직이 없다.** OMS의 유일한 스케줄러 `BackorderSweeper`는 주문 테이블만 보고 승격을
  시도하며, 보충요청의 `status`/`fulfilledAt`을 참조하지 않는다. `FULFILLED`가 늦어져도 오작동하지 않는다.
- `fulfilledAt`/`status`를 쓰는 곳은 관리자 화면 표시 한 곳뿐(`admin/inventory.html:59,62`)이고 값을 그대로
  출력한다. 상태 값의 종류가 늘지 않으므로 표시가 깨지지 않는다.

OMS가 관찰하는 동작 변화 2가지 (모두 무해):

1. **보충요청이 `FULFILLED`가 되는 시점이 늦어진다** — 전량 도착 시로. "아직 안 채워졌다"가 더 정확해진다.
2. **보충 통지 호출이 늘어난다** — 100개가 60/40으로 나뉘면 `POST /api/replenishments`도 2번.
   OMS 측이 자연 멱등(중복 수신 시 승격할 게 없으면 no-op)으로 설계돼 있어 안전하고,
   60개 시점에 백오더 승격이 앞당겨지므로 오히려 개선이다.

---

## 6. 테스트

**도메인** (`PurchaseOrderTest` 신규)
- 부분 입고 누적 → `PARTIALLY_RECEIVED`, `receivedAt`은 여전히 null
- 잔량을 채우면 `RECEIVED` + `receivedAt` 설정
- 초과 입고 거부 / 음수 거부 / `qty=0`은 허용
- 전 품목 0이면 거부
- 모르는 품목ID 거부
- 이미 `RECEIVED`면 거부
- 반환값이 productId 기준 합산이며 0인 항목을 담지 않는다 (같은 상품 2줄 케이스 포함)

**서비스** (`PurchaseOrderServiceTest` 확장)
- 부분 입고 시 **들어온 수량만** `RECEIVE`로 원장에 기록
- `qty=0`인 품목은 원장에 행이 생기지 않는다
- **`fulfill`은 전량 입고 시에만** 호출 — 부분 입고 후 보충요청은 여전히 진행 중
- 초과 입고 예외 시 재고도 원장도 롤백

**마이그레이션** (`InitDbTest` 확장)
- `RECEIVED` 발주 품목은 `receivedQty = quantity`, `ORDERED`는 0으로 백필
- 두 번 실행해도 값이 변하지 않는다 (멱등)

**컨트롤러** (`WmsAdminControllerTest` 확장)
- 상세 페이지 렌더링
- 폼 제출이 서비스로 올바른 `Map`을 전달
- 예외 시 플래시 에러
