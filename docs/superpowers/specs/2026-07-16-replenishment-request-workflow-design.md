# 보충 요청 승인 워크플로우 — 설계

작성일: 2026-07-16
대상: WMS(`jhg-wms-project`) + OMS(`jhg-commerce-project`)

## 배경 / 문제

현재 OMS 관리자 화면(`InventoryAdminController`)은 WMS의 창고 작업을 **직접 원격 변이**한다:

- `wmsInventoryAdapter.adjust(...)` → WMS `POST /api/inventory/adjust`
- `wmsPurchaseOrderAdapter.create(...)` → WMS `POST /api/purchase-orders`
- `wmsPurchaseOrderAdapter.receive(...)` → WMS `POST /api/purchase-orders/receive`

즉 OMS가 남의 바운디드 컨텍스트(WMS)의 실물 수량·발주·입고를 직접 손댄다. 창고에 대한 권한이
요청자(OMS)에게 새어 있는 구조다.

**목표:** OMS를 *요청자*, WMS를 자기 창고에 대한 *권한자*로 되돌린다. OMS는 보충을 **요청**만
하고, WMS 관리자가 검토 후 **승인/반려**한다. 요청·처리 이력의 원본은 WMS가 보관하고 OMS는 API로
조회한다.

## 책임 재배치

| | 제거 | 유지 | 추가 |
|---|---|---|---|
| **OMS** | 수동 재고 조정, 발주 생성, 입고 처리 | WMS 재고 조회, 주문 흐름(reserve/ship/release), 보충 요청 작성·이력 조회 | 보충 요청 폼·이력 화면, 요청 어댑터 |
| **WMS** | (아래 "삭제 대상" 참조) | 자기 admin의 수동 조정·조정 이력·발주·입고, `PurchaseOrderService`(in-process) | `ReplenishmentRequest` 접수·승인·반려, WMS 검토 화면 |

**핵심 불변식:** OMS→WMS의 `reserve/ship/release`(주문 이행)와 재고 **조회**(availability, rows)는
그대로 둔다. 제거 대상은 **수동 관리자 쓰기**뿐(`adjust`, PO `create`, `receive`).

## 데이터 모델 (WMS 원본)

### `ReplenishmentRequest`

| 필드 | 설명 |
|---|---|
| `id` | PK (내부 요청번호) |
| `requestKey` | OMS 요청 폼을 열 때 한 번 생성한 UUID. **UNIQUE 제약** — 멱등 키 |
| `reason` | 요청 사유 (필수) |
| `status` | `REQUESTED / APPROVED / REJECTED / FULFILLED` |
| `requestedAt` | 요청 접수 시각 |
| `decidedAt` | 승인 또는 반려 시각 |
| `fulfilledAt` | 연결된 발주의 입고 완료 시각 |
| `wmsMemo` | WMS 관리자 처리 메모 (승인 시 선택, 반려 시 필수) |
| `purchaseOrderId` | 승인 시 생성된 발주 연결 (nullable, UNIQUE) |

시간 필드를 셋으로 분리한다(`processedAt` 단일 필드 금지). 승인 시각(`decidedAt`)과 입고 완료
시각(`fulfilledAt`)은 서로 다른 사건이며 `APPROVED → FULFILLED` 사이에 리드타임이 있다.

### `ReplenishmentRequestItem`

| 필드 | 설명 |
|---|---|
| `productId` | 품목 (필수, WMS 재고에 존재해야 함) |
| `requestedQty` | 요청 수량 (≥1) |

다품목 요청 → 다라인 PO로 1:1 매핑(기존 `PurchaseOrder` 다품목 구조 재사용).
한 요청 안에서 같은 `productId`를 여러 라인으로 보내는 것은 거부한다.

## 멱등성 (요청 중복 방지)

`id`(PK)만으로는 중복을 못 막는다. 시나리오:

```
OMS 요청 전송 → WMS 저장 성공 → 응답 유실 → OMS 재전송 → 서로 다른 PK의 중복 행 2건
```

승인 중복 방어는 *같은 행*만 보호할 뿐 이 창을 못 막는다. 따라서 **OMS 요청 폼을 열 때 생성한
`requestKey`(UUID)에 WMS UNIQUE 제약**을 둔다. UUID는 폼의 hidden 필드로 전송하고, OMS 컨트롤러와
어댑터는 새로 만들지 않고 전달받은 키를 그대로 사용한다. 전송 내부 재시도도 같은 키를 재사용한다.
기존 `Reservation.orderId` UNIQUE 멱등 패턴과 동일한 관례다.

접수(`POST /api/replenishment-requests`) 처리:

- 동일 `requestKey` + 동일 내용 → 기존 요청을 그대로 반환 (멱등, 자연 재수렴)
- 동일 `requestKey` + 다른 내용 → **409 Conflict**
- 신규 `requestKey` → REQUESTED 저장 후 반환

내용 동일성 판정: 중복 `productId`를 거부한 뒤 품목 순서와 무관한
`Map<productId, requestedQty>`와 reason을 비교한다. UNIQUE 제약이 최종 방어선이며, 동시 삽입 경쟁에서
제약 위반한 요청도 기존 행을 다시 조회해 위 규칙으로 수렴시킨다.

접수 경계 검증:

- `requestKey`와 공백이 아닌 `reason`은 필수
- 품목은 한 개 이상
- `productId`는 필수이며 WMS 재고에 존재해야 함
- `requestedQty`는 1 이상
- 한 요청 안의 동일 `productId` 중복 라인은 거부

## 상태 전이

```
REQUESTED ──승인 / PO 생성──> APPROVED ──PO 입고──> FULFILLED
     └────────── 반려 ──────────> REJECTED
```

- 승인/반려는 `REQUESTED` 상태에서만 허용한다. 도메인 서비스는 이미 처리된 요청의 재처리를 예외로
  거부하고, Thymeleaf 관리자 컨트롤러는 오류 flash 메시지와 목록 화면 redirect로 변환한다.
- 반려 시 공백이 아닌 `wmsMemo`가 필수다. 승인 메모는 선택이다.
- **승인은 발주 생성(ORDERED)까지만.** 입고는 WMS 관리자가 기존 `/admin/purchase-orders`에서 물건
  실제 도착 시 별도로 수행한다. 승인이 곧 입고면 도착 전 재고를 올리고 OMS 백오더를 잘못 승격시킨다.
- `FULFILLED` 전이는 `PurchaseOrderService.receive()`가 담당: **같은 트랜잭션**에서 재고를 증가시키고,
  `purchaseOrderId`로 연결된 요청이 있을 때만 `FULFILLED`로 전이해 `fulfilledAt`을 기록한다. WMS에서
  직접 만든 PO는 연결 요청이 없어도 정상 입고되어야 한다. 기존 OMS 재입고 콜백
  (`OmsReplenishmentNotifier`)은 **커밋 후**(afterCommit) 그대로 실행한다.

## 흐름

```
OMS 관리자
  재고 조회 화면 진입 → requestKey UUID 1회 생성(hidden)
  → 부족 품목·수량·사유 입력 → [OMS→WMS] POST /api/replenishment-requests (같은 requestKey 동봉)
                                                        → WMS: REQUESTED 저장 (멱등)
WMS 관리자 (신규 /admin/replenishment-requests)
  검토 → 반려: status=REJECTED, wmsMemo, decidedAt
       → 승인: PurchaseOrderService.create(요청 라인) → ORDERED PO
               status=APPROVED, purchaseOrderId 연결, decidedAt   ← 여기서 멈춤(입고 아님)
WMS 관리자
  기존 /admin/purchase-orders 에서 해당 PO 입고(receive)  ← 물건 실제 도착 시
       → [한 트랜잭션] 재고 증가 + 연결 요청이 있으면 FULFILLED 전이 + fulfilledAt
       → [커밋 후] OmsReplenishmentNotifier 콜백 → OMS 백오더 승격
OMS 관리자
  이력 화면 → [OMS→WMS] GET /api/replenishment-requests 로 상태 관측
```

## API

### WMS 신규

| Method | URL | 인증/CSRF | 용도 |
|---|---|---|---|
| POST | `/api/replenishment-requests` | Basic, CSRF 예외 | OMS 요청 접수 → REQUESTED (멱등) |
| GET | `/api/replenishment-requests` | Basic | OMS 이력 조회 |
| POST | `/admin/replenishment-requests/{id}/approve` | Basic + CSRF | 승인 → PO 생성 |
| POST | `/admin/replenishment-requests/{id}/reject` | Basic + CSRF | 반려 |
| GET | `/admin/replenishment-requests` | Basic | WMS 검토 화면(Thymeleaf) |

관리자 POST는 REST API가 아니라 HTML 폼 액션이다. 성공과 도메인 전이 오류 모두 목록 화면으로 redirect하고,
오류는 flash 메시지로 표시한다. 별도 승인/반려 REST API는 만들지 않는다.

### OMS 신규

- 보충 요청 폼 GET에서 `requestKey` UUID를 생성해 hidden 필드로 제공
- 보충 요청 어댑터는 폼에서 받은 `requestKey`를 그대로 전송하고 내부 재시도에도 재사용
  (Basic Auth 헤더는 기존 어댑터 관례 재사용)
- 요청 이력 조회 어댑터
- 보충 요청 폼 화면 + 이력 화면(기존 `/admin/inventory` 재고 조회는 유지)

## 삭제 대상 (이번 작업에 포함, 실호출자 확인 완료)

### OMS

- `InventoryAdminController`: `/admin/inventory/adjust`, `/admin/purchase-orders`(GET/POST), `/admin/purchase-orders/receive` 액션 및 PO admin 화면
- `WmsInventoryAdapter.adjust()` **메서드만** — 클래스는 `reserve/ship/release`(주문 이행) 때문에 유지
- `WmsPurchaseOrderAdapter` **전체** (OMS 전용)
- 관련 폼/DTO(`PurchaseOrderForm` 등)와 템플릿

### WMS

- `InventoryController`: `POST /api/inventory/adjust` **만** 제거. `reserve/ship/release` 및 조회(availability, rows) 유지
- `PurchaseOrderController` **전체** — 실호출자가 OMS 어댑터뿐이었고 어댑터 제거 후 호출자 0.
  WMS 자기 admin(`WmsAdminController`)은 `PurchaseOrderService`를 **in-process 직접 호출**하므로 무영향
- `PurchaseOrderRequest` / `PurchaseOrderResponse` DTO (컨트롤러 전용)
- `PurchaseOrderControllerTest` (MockMvc 슬라이스)
- **유지**: `PurchaseOrderService`, `PurchaseOrderForm`, `PurchaseOrder`/`PurchaseOrderItem` 도메인,
  `PurchaseOrderRepository` — WMS admin·승인 플로우가 in-process로 사용

> 계획 단계에서 삭제 직전 각 대상의 참조를 grep으로 한 번 더 확인(회귀 방지).

## 네이밍

- OMS→WMS = **보충 요청(ReplenishmentRequest)**
- 기존 WMS→OMS 콜백(`OmsReplenishmentNotifier` → OMS `/api/replenishments`)은 코드 주석·문서에서
  **"재입고 통지(callback)"**로 구분 표기. 엔티티/클래스 rename은 YAGNI(주석 구분으로 충분).

## 테스트

### WMS

- `ReplenishmentRequest` 상태 전이 단위 테스트 (REQUESTED→APPROVED→FULFILLED, REQUESTED→REJECTED, 잘못된 전이 방어)
- 승인 → PO(ORDERED) 생성 + `purchaseOrderId` 연결 서비스 통합
- PO 입고 → 연결 요청이 있으면 FULFILLED 전이 + `fulfilledAt` (같은 트랜잭션), 콜백 커밋 후
- WMS에서 직접 생성해 연결 요청이 없는 PO도 정상 입고
- 승인/반려 중복 → 서비스 거부, 관리자 화면 오류 flash + redirect
- **멱등: 동일 `requestKey` 재전송 → 기존 요청 반환(중복 행 없음)**
- **충돌: 동일 `requestKey` + 다른 내용 → 409**
- 접수 검증: 빈 reason/items, null·미존재 productId, 0 이하 수량, 중복 productId 거부
- 컨트롤러 슬라이스: 무자격 401, 무CSRF admin POST 403, 인증 성공 3xx redirect,
  잘못된 상태 전이는 오류 flash + 3xx redirect

### OMS

- 보충 요청 폼 GET에서 requestKey 생성, POST와 전송 재시도에서 같은 키 유지
- 보충 요청 전송 어댑터(requestKey 전달·Basic Auth 헤더)
- 이력 조회 어댑터
- 요청 폼 입력 검증과 WMS 오류 표시

## 배포 유의

- 두 서비스(OMS·WMS)는 별도 배포하므로 expand-contract 3단계로 진행한다.
  1. **WMS 확장 배포:** 신규 요청 API·도메인·검토 화면을 추가하되 기존
     `/api/inventory/adjust`, `/api/purchase-orders/**`는 임시 유지
  2. **OMS 전환 배포:** 수동 조정·발주·입고 화면과 어댑터를 제거하고 보충 요청 화면으로 교체
  3. **WMS 정리 배포:** OMS 전환 확인 후 기존 수동 관리자 REST와 전용 DTO·테스트 제거
- 코드 작업 범위에는 기존 API 삭제까지 포함하지만, WMS 확장과 정리는 서로 다른 배포로 내보낸다.
- `ddl-auto: update`로 `replenishment_request(_item)` 테이블 자동 생성. 기존 데이터 마이그레이션 없음.

## 범위 밖 (Deferred)

- 요청 취소(OMS가 REQUESTED 요청 회수), 부분 승인(라인별 수량 조정), 승인자 감사 로그.
- 다중 운영자·역할 분리(현재 단일 admin Basic Auth 유지).
