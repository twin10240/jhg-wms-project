# WMS 관리자 UI 확장 — 디자인 스펙

날짜: 2026-07-09
상태: 승인됨 (A안 — 기존 Thymeleaf MVC 패턴 확장)

## 목표

관리자가 브라우저로 접속해 WMS 현황을 확인하고 기능을 처리하는 서버사이드(Thymeleaf) 관리자 페이지.
대시보드·예약 화면 신규, 재고·발주 화면 보강. 인증 없음, 상품명 없이 상품ID만 표시,
외부 의존성·스키마 변경 없음.

## 결정 사항

| 항목 | 결정 |
|------|------|
| 렌더링 | Thymeleaf SSR, POST → redirect → flash 패턴 유지 |
| 레이아웃 | Thymeleaf fragment (`fragments/layout.html`) + `static/css/admin.css` 하나 |
| 인증 | 없음 (내부망 가정, 필요 시 추후 Spring Security) |
| 상품명 | 표시 안 함 — WMS는 상품ID 기준 운영 |
| JS | 발주 다품목 행 추가/삭제용 바닐라 JS 몇 줄이 유일 |
| 의존성 추가 | 없음 |

## 파일 구조

```
templates/
├── fragments/layout.html     ← 신규: head(title, css) + 상단 네비 + flash fragment
├── admin/dashboard.html      ← 신규
├── admin/inventory.html      ← 보강
├── admin/reservations.html   ← 신규
├── admin/purchaseorders.html ← 보강
└── error.html                ← 신규 (Whitelabel 대체)
static/css/admin.css          ← 신규: 페이지별 인라인 <style> 통합
```

- 상단 네비: 대시보드 | 재고 | 예약 | 발주 — 현재 페이지 강조.
- 포인트 컬러 #f4a261 유지. 상태 배지: RESERVED 파랑, SHIPPED 초록, RELEASED 회색.

## 화면별 스펙

### 대시보드 — `GET /`

루트가 곧 대시보드 (리다이렉트 없음). 요약 카드 3개:

- 재고: SKU 수, 총 보유 / 총 예약 / 총 가용 수량
- 발주: 입고대기(ORDERED) 발주 건수
- 예약: RESERVED / SHIPPED / RELEASED 건수

### 재고 — `GET /admin/inventory` (보강)

- 테이블 컬럼: 상품ID, 보유, 예약, 가용 (현재는 상품ID·보유만).
- 가용수량 0인 행 강조 (품절 시인성).
- 재고 조정 폼 현행 유지 (`POST /admin/inventory/adjust`).

### 예약 — `GET /admin/reservations` (신규)

- 테이블: 예약ID, 주문ID, 상태 배지. ID 역순(최신 먼저) 정렬.
- 상태 필터: 전체 / RESERVED / SHIPPED / RELEASED — 링크 탭 방식 `?status=`.
- 조회 전용. 출고/해제는 품목·수량 맵이 필요한데 Reservation 엔티티(orderId, status만 보유)에
  없으므로 화면 처리 불가 — OMS가 API로 수행하는 현 구조가 맞음.

### 발주 — `GET /admin/purchase-orders` (보강)

- 상태 필터: 전체 / 입고대기 / 입고완료 — `?status=`.
- 입고완료 건에 입고일시(receivedAt) 컬럼 표시.
- 발주 생성 폼에 품목 행 추가/삭제 (바닐라 JS) — 다품목 발주 지원
  (서비스는 이미 지원, 폼만 1품목 제한이었음).

### 에러 페이지 — `templates/error.html` (신규)

상태코드 + 홈(대시보드) 링크만 있는 단순 페이지. Whitelabel 대체.

## 백엔드 변경 (최소)

- `WmsAdminController`: `GET /` (대시보드), `GET /admin/reservations` 추가.
  발주·예약 목록 핸들러에 status 쿼리 파라미터 (선택적).
- `InventoryService.findAllRows()`: 예약수량 포함하도록 수정
  → `InventoryRowResponse`에 `reservedQty`, `availableQty` 필드 추가.
  REST `/api/inventory/rows` 응답에도 필드가 추가되지만 additive라 기존 소비자 안 깨짐.
- `InventoryService.findAllReservations()` 추가 — `reservationRepository.findAll()`
  한 줄 위임 (컨트롤러는 기존 관례대로 서비스만 의존, 리포지토리 직접 접근 없음).
- 대시보드 집계는 별도 메서드 없이 `WmsAdminController`가 기존 조회
  (`findAllRows()`, `findAllWithItems()`, `findAllReservations()`) 결과를
  자바에서 합산해 모델에 담는다.
- 예약 상태 필터는 자바 스트림에서 — 학습 규모라 충분.
- 스키마 변경 없음.

## 에러 처리

- 폼 검증 실패·비즈니스 예외: 기존 flash(`successMessage`/`errorMessage`) 패턴 그대로.
- 매핑 없는 URL·서버 에러: `error.html`.

## 테스트

기존 MockMvc 슬라이스 패턴으로 `WmsAdminControllerTest` 확장(또는 신규):

- `GET /` 200 + 대시보드 모델 값 검증
- `GET /admin/reservations` 상태 필터 동작 검증
- 재고 화면 모델의 reservedQty/availableQty 값 검증

## 범위 밖 (명시적 제외)

- 인증/권한, 상품명 표시, Reservation 스키마 확장(품목·시각),
  화면에서의 출고/해제 처리, 페이징(데이터 소량).
