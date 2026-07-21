# WMS 비즈니스 로드맵

작성: 2026-07-21. 현재 도메인은 "OMS 연동 재고 이행 원장"으로는 탄탄하나, WMS의 창고관리 핵심 몇 개가 비어 있다.
이 문서는 그 갭을 **의존성 순서**로 배치한 진행 계획이다. 학습/포트폴리오 프로젝트이므로 전부 구현이 목표가 아니라,
**"WMS를 자처하는데 결정적으로 없는 것"부터 가성비 순으로** 채우는 것이 목표.

## 시퀀싱 원칙

1. **원장(ledger) 먼저.** 실사·부분입고·반품이 전부 "재고 이동을 이력으로 남긴다"를 전제로 한다 → 트랜잭션 원장이 토대.
2. **작고 임팩트 큰 것 우선.** 이미 반쯤 있는 것(원장), 상태·수량만 더하면 되는 것(부분입고)을 앞에.
3. **`Inventory`를 갈아엎는 것은 뒤로.** 위치(Location)는 엔티티 재설계라 프로젝트를 2배로 키움 → 서사에 필요할 때만.

---

## 현황 (있는 것)

- 재고 수량 모델 `onHand / reserved / available` + `@Version` 낙관적 락 — `domain/Inventory.java`
- 예약 원장 `Reservation`(orderId 멱등, SSOT) — `domain/Reservation.java`
- 발주 `ORDERED→RECEIVED` — `domain/PurchaseOrder.java`
- 보충요청 워크플로우(OMS 요청→WMS 승인→발주→입고→이행) — `domain/ReplenishmentRequest.java`
- 수동 조정 원장 `InventoryAdjustment`(insert-only, 수동만) — `domain/InventoryAdjustment.java`
- OMS 재고보충 통지(채널3) — `client/OmsReplenishmentNotifier.java`

---

## 진행 리스트 (우선순위·의존성 순)

### Phase 1 — 재고 트랜잭션 원장 (토대)  ⭐가장 먼저
**왜:** `InventoryAdjustment` 주석에 이미 "추후 전체 재고 트랜잭션 원장으로 확장" 명시됨. 지금은 수동 조정만
기록되고 입고·출고·예약 이동은 원장에 안 남아 "이 수량이 왜 이렇게 됐나"를 역추적 불가. 감사추적은 WMS 신뢰성의 핵심.
**범위:** `InventoryAdjustment`를 일반 `InventoryTransaction`(또는 `StockMovement`)으로 승격 — 사유 타입(RECEIVE / SHIP / RESERVE / RELEASE / ADJUST / RETURN / COUNT), before/after, 참조(orderId·poId), 발생시각.
모든 재고 변경 경로(`InventoryService`)가 이 원장에 한 줄씩 남기도록 배선.
**터치:** `domain/InventoryAdjustment.java`(승격) · `service/InventoryService.java`(전 경로에 기록) · 관리자 UI에 이력 화면.
**완료 기준:** 임의 재고 변경 후 원장만 보고 현재 수량을 재구성할 수 있다.

### Phase 2 — 부분 입고 + 입고 검수
**왜:** `PurchaseOrder.receive()`가 전량 한 방 `RECEIVED`. 현실은 "100개 발주 → 60개 도착". 발주-입고 흐름의 현실성이 확 오름.
**범위:** 발주 품목별 `orderedQty` vs 누적 `receivedQty`. 부분 입고 시 `PARTIALLY_RECEIVED` 상태 추가, 전량 도착 시 `RECEIVED`.
(선택) 입고 검수 — 불량/수량불일치 반려. 입고 시 Phase 1 원장에 RECEIVE 기록.
**터치:** `domain/PurchaseOrder.java` · `domain/PurchaseOrderItem.java`(receivedQty) · `domain/PurchaseOrderStatus.java`(PARTIALLY_RECEIVED) · `service/PurchaseOrderService.java` · UI.
**완료 기준:** 한 발주를 여러 번에 나눠 입고할 수 있고, 미도착 잔량이 보인다.

### Phase 3 — 재고 실사 (Cycle Count)
**왜:** 장부 vs 실물 차이를 주기적으로 맞추는 프로세스. Phase 1 원장 위에서 "실사→차이→조정" 워크플로우로 자연스럽게 얹힘.
**범위:** 실사 세션 생성 → 실물 카운트 입력 → 차이 계산 → 승인 시 조정 반영(원장에 COUNT 기록).
**터치:** 신규 `StockCount` 엔티티 · `service` · UI. Phase 1 의존.
**완료 기준:** 실사로 productId 실물 수량을 입력하면 차이가 조정으로 반영되고 원장에 남는다.

### Phase 4 — 반품 / RMA
**왜:** 지금 `release`는 예약 해제일 뿐 반품 입고가 아니다. 고객 반품 재입고 경로가 없음.
**범위:** 반품 접수 → (검수) → 재입고 or 폐기. 재입고 시 onHand 증가 + 원장 RETURN 기록.
**터치:** 신규 `Return` 엔티티 · `service` · UI. Phase 1 의존.
**완료 기준:** 출고된 주문의 일부를 반품 재입고 처리하면 재고가 늘고 이력이 남는다.

### Phase 5 — 재주문 자동화 (안전재고 / 재주문점)
**왜:** 지금 보충은 OMS가 트리거. WMS가 스스로 "안전재고 밑돌면 발주 제안"하지 않음.
**범위:** `Inventory`에 `safetyStock`·`reorderPoint`. availableQty가 재주문점 밑돌면 발주 제안(초안 PO 또는 알림).
**터치:** `domain/Inventory.java` · 신규 스케줄/서비스 · UI 대시보드 경고.
**완료 기준:** 재고가 재주문점 아래로 떨어지면 보충 제안이 뜬다.

### Phase 6 — 위치(Location / Bin) · 다중 창고  🏗️대공사·선택
**왜:** WMS의 W(warehouse) 본질 = "물건이 창고 어디에 있냐". 지금은 productId당 수량 한 덩어리 → 사실상 재고 원장.
**범위:** 창고/존/랙/빈 로케이션 모델, 재고를 (product, location)별로 분해, 적치(putaway)·피킹 경로.
**주의:** `Inventory` 엔티티 재설계 — 프로젝트를 2배로 키움. 포트폴리오 서사에 "진짜 WMS"가 필요할 때만 착수.
**완료 기준:** 같은 상품이 여러 위치에 나뉘어 있고, 입출고가 위치 단위로 일어난다.

---

## Deferred / YAGNI (당분간 안 함)

- **Lot / Serial / 유통기한(FEFO)** — 식품·의약품 아니면 불필요.
- **공급업체 마스터 심화**(단가·리드타임·ETA) — Phase 2에서 최소만, 본격 관리는 보류.
- **역할 분리(작업자 vs 관리자)** — 이미 `todo_list_codex.md`에 인지됨. 다중 운영자 생길 때.

---

## 추천 착수 순서

**Phase 1(원장) → Phase 2(부분 입고)** 두 개만 채워도 "재고 정합성을 진지하게 다뤘다"는 인상이 확 생긴다. 가성비 최고.
Phase 3~5는 원장 위에서 점진 확장. Phase 6(위치)은 별도 큰 결심.
