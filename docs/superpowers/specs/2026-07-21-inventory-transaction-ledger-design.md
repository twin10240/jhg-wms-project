# 재고 트랜잭션 원장 (Phase 1) — 설계

작성: 2026-07-21. 로드맵(`docs/wms-business-roadmap.md`) Phase 1.

## 목적

지금은 **수동 조정만** `InventoryAdjustment`에 기록되고 발주 입고·출고는 원장에 안 남아
"이 수량이 왜 이렇게 됐나"를 역추적할 수 없다. 실물(onHand)을 바꾸는 모든 경로가 원장에 한 줄씩 남겨,
**원장만으로 현재 수량을 0부터 재구성**할 수 있게 한다. 감사추적은 WMS 신뢰성의 핵심이며,
이후 실사(Phase 3)·반품(Phase 4)이 이 원장 위에 얹힌다.

## 범위

- **기록 대상: 실물(onHand) 변동만** — OPENING·RECEIVE·SHIP·ADJUST.
- 예약/해제(`reservedQty`만 변경)는 **제외** — 이미 `Reservation` 원장이 담당(이중부기 회피).
- RETURN·COUNT 타입은 Phase 3/4에서 추가(지금 enum에 넣지 않음, YAGNI).

## 결정 요약

| 결정 | 선택 |
|------|------|
| 원장 범위 | onHand 변동만 (+ reference 필드) |
| 초기 재고 | OPENING 트랜잭션으로 기록 → 0부터 완결 재구성 |
| 엔티티 전략 | `InventoryAdjustment` → `InventoryTransaction` 승격(리네임+필드 추가) |

---

## 1. 데이터 모델 — `InventoryTransaction`

기존 `InventoryAdjustment`의 5개 필드를 재사용하고 2개를 추가한다.

```java
@Entity  // 테이블: inventory_transaction (기존 inventory_adjustment 리네임)
class InventoryTransaction {
    Long id;
    Long productId;
    InventoryTransactionType type;   // ★신규
    int delta;                       // 부호 있음: RECEIVE/OPENING +, SHIP -, ADJUST ±
    int beforeQty;
    int afterQty;
    String reference;                // ★신규 nullable: "PO#12", "ORDER#34"; OPENING·수동조정은 null
    String reason;                   // 기존: 수동조정 메모
    LocalDateTime createdAt;
}

enum InventoryTransactionType { OPENING, RECEIVE, SHIP, ADJUST }
```

- `reference`는 type+id로 쪼개지 않고 **nullable String 하나**로 둔다 — 표시·추적엔 충분. 조인이 실제로 필요해지면 그때 분리(YAGNI).
- `insert-only` 유지(원장은 불변).

## 2. 기록 무결성 — "onHand가 바뀌면 반드시 원장 1행"

onHand를 바꾸는 지점을 코어 하나로 모아 모든 경로가 통과하게 한다.

```java
// InventoryService 신규 private — onHand 변경 + 원장 기록을 한 곳에서
private int applyDelta(Long productId, int delta, InventoryTransactionType type,
                       String reference, String reason) {
    // 1. inv 로드, before 캡처
    // 2. 기존 가드 유지: onHand 0 미만 방어, 예약분(reservedQty) 미만으로 감소 방어
    // 3. onHand 적용, after 캡처
    // 4. InventoryTransaction 저장  ← 항상 기록
    // 5. delta > 0 이면 OMS 재고보충 통지(기존 notifyAfterCommit)
}
```

경로별 매핑:

| 경로 | 호출 | type | reference |
|------|------|------|-----------|
| 발주 입고 `PurchaseOrderService.receive` | `applyDelta(pid, +qty, RECEIVE, "PO#"+poId, null)` | RECEIVE | PO#id |
| 수동 조정 `WmsAdminController` | `applyDelta(pid, ±delta, ADJUST, null, reason)` | ADJUST | null |
| 출고 `InventoryService.shipAll` | ship 적용 시 SHIP 기록 | SHIP | ORDER#id |
| 시드 `InitDb` | `applyDelta(pid, +초기, OPENING, null, null)` | OPENING | null |

- 기존 2-arg `adjust(pid, delta)` / 3-arg `adjust(pid, delta, reason)`는 `applyDelta`로 흡수, 시그니처 정리.
- **출고 특수 처리:** `Inventory.ship(qty)`는 onHand·reserved를 동시에 깎는다. 원장엔 onHand 델타(−qty)만
  SHIP으로 남긴다(reserved 이동은 Reservation 원장 담당). `shipAll`은 예약 원장(SSOT)을 재생하므로,
  재생된 상품별 수량으로 SHIP 트랜잭션을 상품당 1행 기록한다.

## 3. OPENING 백필 — 신규·기존 모두 완결

- **신규 기동(빈 DB):** `InitDb`가 시드하면서 상품별 OPENING 트랜잭션을 남긴다 → 원장이 0부터 완결.
- **기존 prod(재고 이미 있음):** 기동 시 1회, 원장에 OPENING이 없는 상품은 OPENING으로 소급 기록.
  단 delta는 현재 onHand 전체가 아니라 **원장 잔여분**(`현재onHand - 기존 델타합`, `before=0, after=opening`) —
  구 조정행이 이미 원장에 있는 상태(prod)에서 onHand 전체를 다시 더하면 이중계상되어 완료 기준(Σdelta==onHand)이
  깨지기 때문. 이미 있으면 skip(멱등).

## 4. 기존 데이터·prod 스키마 마이그레이션 (유일한 운영 리스크)

- `inventory_adjustment` → `inventory_transaction` 리네임 + `type`·`reference` 컬럼 추가.
- `ddl-auto: update`는 컬럼 추가는 하지만 **테이블 리네임·기존 행 백필은 하지 않는다** →
  기존 행이 새 `type` 컬럼 NULL로 남아 원장 조회가 깨질 위험.
- **처리(애플리케이션 기동 루틴, 멱등):**
  1. `type`을 nullable로 정의(기존 행 수용).
  2. 기동 시 마이그레이션 루틴이 구 `inventory_adjustment` 데이터를 새 원장으로 이관하며
     `type=ADJUST`로 채운다(기존 조정은 전부 수동조정이었으므로 정확). prod 데이터량이 적어 기동시 1회로 충분.
  3. OPENING 백필(3절)을 이어서 수행.
- Flyway 미도입 프로젝트이므로 이 마이그레이션은 **기동 루틴**으로 한다(`InitDb`와 동일 결). 전부 멱등.
- 로컬 H2는 `ddl-auto` 재생성이라 무관.
- **as-built 편차:** 실제 구현은 "테이블 리네임 + 크로스테이블 이관" 대신 **테이블명(`inventory_adjustment`) 유지 + `type`·`reference` 컬럼 추가 + 기존 행 NULL→ADJUST 백필**로 처리했다(운영 리스크 감소, 외부 동작 동일).

## 5. 조회 화면

- 기존 "수동 조정 내역" → **"재고 트랜잭션 이력"**으로 확장. type 필터(전체/입고/출고/조정/기초).
- `InventoryService.findAllAdjustments()` → `findAllTransactions()`, 최신순 유지.
- 대시보드 최근 이동 요약은 넣지 않는다(YAGNI, 필요 시 후속).

## 6. 테스트

- 기존 `InventoryAdjustment` 관련 테스트를 트랜잭션 이름·필드로 반영.
- **재구성 불변식(핵심 체크):** 입고 → 출고 → 조정 시퀀스 후
  `Σ(원장 delta) == 현재 onHand` 를 검증하는 테스트 1개. Phase 1 완료 기준의 실행 가능한 증거.
- OPENING 백필 멱등성 테스트(두 번 돌려도 OPENING 중복 없음).

## 완료 기준

임의의 재고 변경(입고·출고·조정) 후, 원장의 델타 합만으로 각 상품의 현재 onHand를 재구성할 수 있다.
기존 prod 조정 이력은 유실 없이 원장으로 이관되고, 초기 재고는 OPENING으로 소급 기록된다.

## 비범위 (Phase 1 아님)

- RETURN(반품)·COUNT(실사) 타입 — Phase 3/4.
- 예약/해제의 원장화 — Reservation 원장이 담당.
- reference의 정규화(조인 가능한 참조 테이블) — 필요해질 때.
- 대시보드 이동 요약 위젯.
