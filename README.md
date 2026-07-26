# JHG-WMS

[![CI](https://github.com/twin10240/jhg-wms-project/actions/workflows/ci.yml/badge.svg)](https://github.com/twin10240/jhg-wms-project/actions/workflows/ci.yml)

**주문 시스템과 창고 시스템을 물리적으로 분리하고, 그 사이에서 재고 정합성을 지키는 WMS입니다.**

주문(OMS)과 재고(WMS)를 별개 애플리케이션·별개 DB로 나누면 "재고가 몇 개인가"라는 질문에 두 개의 답이 생길 위험이 따라옵니다. 이 프로젝트는 **재고의 정본을 WMS 한 곳에 두고**, OMS는 그 재고를 실시간으로 조회·차감하기만 하도록 경계를 그었습니다. 그리고 그 경계 위에서 **예약 모델**(가용 = 실물 − 예약)로 오버셀을 막고, **모든 재고 이동을 원장에 남겨** "이 수량이 왜 이렇게 됐는지"를 역추적할 수 있게 했습니다.

핵심은 **상대 시스템이 죽어도 재고 데이터가 오염되지 않는 것**입니다 → [회복탄력성](#회복탄력성--상대가-죽어도-재고는-오염되지-않는다)

| | |
|---|---|
| 통신 채널 | 4개 (조회 · 이행 · 통지 · 보상) |
| 재고 원장 | `OPENING / RECEIVE / SHIP / ADJUST` — 불변식 Σdelta == onHand |
| 접근제어 | 폼 로그인 + `OPERATOR`/`MANAGER` 롤, `/api`는 서비스 계정 Basic |
| 테스트 | 156개 (도메인 · 서비스 · MockMvc 슬라이스 · **실서블릿 보안 통합**) |

## OMS ↔ WMS 책임 경계

**주문(OMS)** 과 **창고(WMS)** 를 물리적으로 분리된 두 앱으로 나누고, 서로 직접 의존하지 않고 **REST 계약**으로만 통신합니다. 재고의 정본(source of truth)은 WMS 한 곳입니다.

| | OMS (jhg-commerce, :8080) | WMS (이 저장소, :8081) |
|---|---|---|
| 소유 도메인 | 주문·장바구니·고객·판매, 백오더 | 재고 수량·예약·발주(PO)·입고·재고 원장 |
| 재고에 대해 | 조회(실시간 질의) + "보충해줘" 요청 | 재고 정본. 수동 조정·발주·입고·요청 승인 |
| 관리자 권한 | 재고 조정·발주·입고 **없음**(설계상 제거) | 위 전부 소유 (OPERATOR/MANAGER 롤) |
| DB | 주문·고객 (재고 수량 없음) | 재고·예약·발주·원장 |

**OMS는 재고 수량을 저장하지 않습니다.** 필요할 때마다 WMS에 HTTP로 조회/차감하므로 어긋날 사본이 없습니다(미러 아님 — 라이브 리드).

### 통신 채널 (서비스 계정 Basic 인증)

| 채널 | 방향 | 용도 |
|------|------|------|
| S1 | OMS → WMS | 가용수량 조회 `GET /api/inventory/availability` |
| S2 | OMS → WMS | 주문 이행 `reserve` / `ship` / `release` |
| S3 | WMS → OMS | 재고 증가(입고·조정) 통지 → OMS 백오더 FIFO 승격 |
| S4 | 양방향 | 회복탄력성 — 타임아웃 · best-effort · 보상 스윕 |

보충 흐름: OMS가 백오더로 부족을 감지 → WMS에 **보충 요청** → WMS 관리자가 **승인 → 발주 생성 → 입고** → 재고 증가 → S3로 OMS에 통지 → OMS가 백오더 승격. (상세: 아래 [보충 요청과 발주](#보충-요청과-발주) · [OMS 재고보충 통지](#oms-재고보충-통지-s3-채널3) 절)

> 핵심: **"몇 개 있냐"는 오직 WMS.** OMS는 그 재고를 실시간으로 조회·차감할 뿐 자기 수량을 갖지 않는다. WMS가 응답하지 않으면 OMS는 가용수량 0으로 폴백(품절/백오더)해 무너지지 않는다 — 재고 정본을 한 곳에 두면서 그 의존이 끊겨도 견디도록 설계.

## 스택

| 항목 | 내용 |
|------|------|
| Java | 21 |
| Spring Boot | 3.5.5 |
| JPA / Hibernate | Spring Data JPA |
| DB | H2 TCP (OMS와 물리 분리) |
| 빌드 | Gradle |

## 실행

H2 서버를 먼저 띄운 뒤 애플리케이션을 실행합니다.

```bash
# H2 서버 (별도 터미널)
java -cp h2*.jar org.h2.tools.Server -tcp -tcpAllowOthers -ifNotExists

# WMS 실행 (포트 8081 — OMS가 8080 사용)
./gradlew bootRun

# 스키마 리셋이 필요할 때
./gradlew bootRun --args='--spring.profiles.active=local'
```

H2 콘솔: `http://localhost:8081/h2-console`  
JDBC URL: `jdbc:h2:tcp://localhost/~/jhg-wms`

### 로컬 계정 (기동 시 자동 시드)

| 구분 | 계정 | 용도 |
|------|------|------|
| 관리자(MANAGER) | `manager` / `manager` | 폼 로그인 — 발주 생성·취소, 보충요청 승인·반려 포함 전 기능 |
| 운영자(OPERATOR) | `operator` / `operator` | 폼 로그인 — 조회·재고 조정·발주 입고 |
| 서비스 계정 | `wms` / `wms` | `/api/**` HTTP Basic — OMS 서버간 호출 전용(사람 로그인 아님) |

로그인 페이지는 `http://localhost:8081/login`. 운영에서는 셋 다 환경변수로 주입하며, 비어 있으면 기동이 실패합니다(fail-fast).

## 운영 배포 (Railway)

> 배포 설정과 과거 운영 검증 기록은 보존돼 있지만 **현재 Railway 서비스는 중단 상태**입니다.

- Dockerfile(멀티스테이지 JDK21) 존재 시 Railway가 자동 사용. `.dockerignore`로 build/·.git/ 제외.
- `prod` 프로파일: PostgreSQL(PG* 변수), `ddl-auto: update`, H2 콘솔 off. 빈 DB면 `InitDb`가 재고 1~20 시드.
- Variables:
  - `SPRING_PROFILES_ACTIVE=prod`, `PORT=8081`(private networking 주소 고정용), `OMS_BASE_URL=http://<oms>.railway.internal:8080`
  - `WMS_BASIC_USER`/`WMS_BASIC_PASSWORD` — `/api/**` 서비스 계정. **OMS 서비스에도 동일 값 필수**
  - `WMS_OPERATOR_USER`/`WMS_OPERATOR_PASSWORD`, `WMS_MANAGER_USER`/`WMS_MANAGER_PASSWORD` — 관리자 폼 로그인 계정 시드
  - `OMS_CALLBACK_USER`/`OMS_CALLBACK_PASSWORD` — WMS→OMS 재고보충 통지(S3)용. **OMS 서비스에도 동일 값 필수**
- **prod는 위 자격증명에 기본값이 없습니다** — 누락·공백이면 기동이 실패합니다(운영에서 `wms/wms` 같은 기본값으로 조용히 뜨는 것을 차단).
- 재배포 시 공개 도메인의 관리자 화면은 폼 로그인, `/api/**`는 Basic을 사용한다.
  OMS↔WMS는 private networking(`WMS_BASE_URL`/`OMS_BASE_URL`)으로 통신한다.
- 주의: `org.gradle.java.home`은 레포 `gradle.properties`에 커밋 금지(Windows 경로가 컨테이너 빌드를 죽임) — 머신 로컬 `~/.gradle/gradle.properties`에서 지정한다.

## API

### 재고 조회

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/inventory/availability?productIds=1,2,3` | 가용수량 맵 반환 (OMS 채널1 연동) |
| GET | `/api/inventory/rows` | 전체 재고 목록 (관리자) |

### 주문 이행 재고 쓰기

| Method | URL | Body | 설명 |
|--------|-----|------|------|
| POST | `/api/inventory/reserve` | `{"orderId":1,"items":{"1":3,"2":1}}` | 예약 (멱등) |
| POST | `/api/inventory/ship` | `{"orderId":1,"items":{"1":3,"2":1}}` | 출고 |
| POST | `/api/inventory/release` | `{"orderId":1,"items":{"1":3,"2":1}}` | 예약 해제 |

수동 재고 조정·발주 생성·입고 처리는 WMS 관리자 UI와 내부 서비스가 소유합니다. 이를 원격으로 수행하던 legacy `/api/inventory/adjust` 및 `/api/purchase-orders` 쓰기 REST는 삭제되었습니다.

### 재고 상태 흐름

```
onHandQty: 실물 수량
reservedQty: 예약 수량
availableQty = onHandQty - reservedQty

reserve  → reservedQty +qty
ship     → onHandQty -qty, reservedQty -qty
release  → reservedQty -qty
adjust   → onHandQty ±delta (예약분 미만·음수 방어)
```

발주 입고·수동 조정·초기 시드는 `InventoryService.applyDelta` 한 곳을 통과하며, 출고는 예약분 동시 차감 때문에
별도 경로로 SHIP 트랜잭션을 기록한다 — 모든 경로가 `InventoryTransaction` 원장에 한 행씩 남긴다(OPENING/RECEIVE/SHIP/ADJUST).
불변식: 상품별 원장 delta 합 == 현재 onHandQty.

### 보충 요청과 발주

| Method | URL | Body | 설명 |
|--------|-----|------|------|
| GET | `/api/replenishment-requests` | | 보충 요청 원본·이력 조회 |
| POST | `/api/replenishment-requests` | `{"requestKey":"...","reason":"...","items":[...]}` | OMS 보충 요청 접수 (신규 201, 멱등 재요청 200) |

OMS는 보충을 요청하고 이력을 관측할 뿐이며, 요청 원본과 수동 재고 조정·발주·입고는 WMS가 소유합니다. WMS 승인은 `ORDERED` 발주를 생성하고 요청을 `APPROVED`로 연결합니다.

발주는 `ORDERED → PARTIALLY_RECEIVED → RECEIVED` 세 단계로 진행됩니다. 한 발주를 여러 차례에 나눠 입고할 수 있고, 품목별로 누적 입고량과 잔량(`remainingQty`)을 추적합니다 — 발주 내 모든 품목의 잔량이 0이 되어야 `RECEIVED`로 전이하고, 그 전까지는 `PARTIALLY_RECEIVED`에 머무릅니다. 연결된 요청은 발주가 `RECEIVED`가 되는 시점(전량 입고)에만 `FULFILLED`로 전이합니다 — 부분 입고 단계에서 이행 통지를 보내면 "요청 물량을 채웠다"는 거짓 신호가 되기 때문입니다.

#### 발주 취소 (생애주기의 출구)

부분 입고는 "공급처가 잔량을 영영 안 보내는" 상태를 만들 수 있습니다. 그대로 두면 발주가 `PARTIALLY_RECEIVED`에 갇히고 연결된 요청도 종결되지 못하므로, **MANAGER가 발주를 취소**해 닫습니다.

```
ORDERED ─────────┐
                 ├── cancel() ──▶ CANCELLED   (연결 요청도 CANCELLED, 한 트랜잭션)
PARTIALLY_RECEIVED┘
RECEIVED ── 취소 불가(이미 완료)
CANCELLED ── 재입고 불가
```

- **이미 입고된 수량은 되돌리지 않습니다** — 실물이 창고에 들어왔으므로 재고·원장 모두 불변(역산 없음, 신규 원장 행 없음).
- 취소된 발주에는 입고 버튼이 노출되지 않고, 직접 요청해도 도메인이 거부합니다.
- 연결된 보충 요청은 같은 트랜잭션에서 `CANCELLED`로 종결됩니다 — 필요하면 OMS가 새로 요청합니다.

### OMS 재고보충 통지 (S3, 채널3)

재고가 늘어나면(발주 입고, +조정) `OmsReplenishmentNotifier`가 트랜잭션 커밋 후 OMS `POST /api/replenishments` 에 `{"productIds":[...]}` 를 보냅니다 — OMS가 백오더를 FIFO 승격.

- 발화점은 `InventoryService.applyDelta` 한 곳 — 모든 재고 증가 경로(입고·WMS UI 조정)가 통과
- best-effort: OMS가 다운이어도 입고/조정은 성공, warn 로그만 남김 (누락 승격은 S4 보상 스윕이 커버)
- 통지는 자연 멱등(사실 전달뿐) — 중복 수신 시 OMS 쪽 no-op
- 콜백 대상: `oms.base-url` (기본 `http://localhost:8080`)
- 통지·전 REST 응답에 타임아웃(connect 1s / read 2s, `spring.http.client.*`) — OMS hang이어도 최대 수 초 내 복귀 (S4)
- `shipAll`은 RELEASED 예약 출고를, `releaseAll`은 SHIPPED 예약 해제를 거부(S4) — 타임아웃 반쪽 상태에서의 재고 오염(reservedQty 음수) 방지

### 관리자 UI (Thymeleaf)

| URL | 설명 | 권한 |
|-----|------|------|
| `/login` | 폼 로그인 (로그아웃·오류 안내) | 공개 |
| `/` | 대시보드 — **처리 대기**(검토 대기 요청·부분입고 발주·가용 0 SKU)·재고·발주·예약 요약, 각 항목이 해당 목록으로 이동 | 인증 |
| `/admin/inventory` | 재고 조회(보유·예약·가용)·수동 조정 | 인증 |
| `/admin/inventory/transactions` | 재고 트랜잭션 이력 — 유형 필터(기초/입고/출고/조정), 상품명·변경 전→후·참조(`발주 #N`/`주문 #N`)·사유 표시, 최신 200건 | 인증 |
| `/admin/reservations` | 예약 현황 조회 — 상태 필터, 주문별 상품·수량 표시 (조회 전용) | 인증 |
| `/admin/purchase-orders` | 발주 목록(`ORDERED`/`PARTIALLY_RECEIVED`/`RECEIVED`/`CANCELLED` 필터) — 미완료를 발주일시 오래된 순으로, 종료된 건은 뒤로 | 인증 |
| `/admin/purchase-orders` (POST) | 발주 생성(다품목) | **MANAGER** |
| `/admin/purchase-orders/{poId}` | 발주 상세 — 품목별 발주량·입고량·잔량, 입고 처리(여러 번 나눠 입고) | 인증 |
| `/admin/purchase-orders/{poId}/cancel` | 발주 취소 | **MANAGER** |
| `/admin/replenishment-requests` | OMS 보충 요청 검토·이력 조회 | 인증 |
| 승인·반려 (POST) | 보충 요청 승인·반려 | **MANAGER** |

상태는 화면에 **한글로 표시**합니다(발주됨/부분 입고/입고 완료/취소됨, 검토 대기/발주 진행/반려/입고 완료, 예약/출고 완료/예약 해제) — enum 원문은 노출하지 않습니다.

### 인증·인가 — 보안 체인 2분할

사람이 쓰는 관리자 화면과 OMS 서버간 호출은 요구사항이 다릅니다(세션/CSRF vs 무상태 Basic). 그래서 `SecurityFilterChain`을 **둘로 분리**합니다.

| 체인 | 경로 | 인증 | 대상 | CSRF |
|------|------|------|------|------|
| `@Order(1)` | `/api/**` | HTTP Basic — 서비스 계정(`WMS_BASIC_USER`/`WMS_BASIC_PASSWORD`) | OMS 서버간 호출 | 예외 |
| `@Order(2)` | `/`·`/admin/**` | **폼 로그인** — DB 유저(`WmsUser`, BCrypt) | 사람(운영자·관리자) | 활성 |

- **`/api/**`는 인증 실패 시 401을 직접 응답합니다**(`HttpStatusEntryPoint`) — 폼 로그인 리다이렉트(302)로 새면 호출자가 "성공"으로 오인하기 때문입니다. 실제로 OMS 쪽에서 같은 원인의 302 버그를 겪어, 이 동작을 **실서블릿 통합 테스트로 고정**해두었습니다(MockMvc는 서블릿의 `/error` 재디스패치를 재현하지 못함).
- 롤은 `OPERATOR`/`MANAGER` 두 가지이며 DB에서 로드합니다. **서버가 최종 권위**(`hasRole`)이고, 화면의 버튼 숨김은 보조 수단입니다 — 권한 없는 경로로 직접 POST해도 403입니다.
- 자격증명 변경 시 OMS 쪽 변수도 함께 바꿀 것(안 그러면 OMS→WMS 전면 401).

### 예약 멱등성

`Reservation` 엔티티가 `orderId`에 `UNIQUE` 제약을 가집니다.  
동일 `orderId`로 재요청 시 현재 상태(`RESERVED/SHIPPED/RELEASED`)를 그대로 반환합니다.

## 회복탄력성 — 상대가 죽어도 재고는 오염되지 않는다

두 시스템으로 나누면 **상대가 응답하지 않는 순간**이 반드시 생깁니다. 이 프로젝트는 그 순간을 예외가 아니라 **정상 경로의 일부**로 두고 설계했습니다. 원칙은 하나입니다 — *통신은 실패해도 되지만, 재고 데이터는 틀리면 안 된다.*

| 실패 상황 | 설계 대응 | 결과 |
|-----------|-----------|------|
| **OMS가 죽은 채로 입고 발생** | 통지는 best-effort — 실패해도 입고·원장은 커밋(`OmsReplenishmentNotifier`가 예외를 삼킴) | 재고 무손실. 승격만 지연되고, OMS 복구 후 **보상 스윕**이 누락분 회수 |
| **WMS가 죽은 채로 주문 유입** | OMS 어댑터가 가용수량을 **0으로 폴백** | 주문은 실패하지 않고 백오더로 접수. 없는 재고를 팔지 않음 |
| **상대가 응답 없이 매달림(hang)** | RestClient 타임아웃 (connect 1s / read 2s) | 스레드가 묶이지 않고 수 초 내 복귀 |
| **타임아웃으로 생긴 반쪽 상태** | `shipAll`은 RELEASED 예약 출고를, `releaseAll`은 SHIPPED 예약 해제를 **거부** | `reservedQty` 음수 같은 재고 오염 차단 |
| **중복 요청 · 재시도** | 예약은 `orderId` UNIQUE로 멱등, 보충 요청은 `requestKey`로 멱등, 통지는 사실 전달이라 자연 멱등 | 재시도해도 수량이 두 번 반영되지 않음 |
| **자격증명 오설정으로 조용한 전면 실패** | 운영 프로파일은 자격증명에 **기본값 없음** → 누락·공백이면 기동 실패(fail-fast) | `wms/wms` 같은 기본값으로 운영에 뜨는 사고 차단 |
| **인증 실패가 성공으로 오인** | `/api/**`는 인증 실패 시 **401을 직접 응답**(폼 로그인 302로 새지 않게) | 호출자가 로그인 페이지를 200으로 받아 "성공"으로 착각하는 문제 차단 |

> 마지막 항목은 실제로 겪은 버그입니다. OMS 콜백 인증을 붙였을 때 인증 실패가 302(로그인 리다이렉트)로 응답돼, WMS가 이를 실패로 인지하지 못하고 조용히 넘어갔습니다. 원인은 서블릿이 401을 `/error`로 재디스패치하면서 폼 로그인 체인에 걸린 것이었고, MockMvc는 이 재디스패치를 재현하지 못해 테스트도 통과하고 있었습니다. 그래서 **실서블릿 통합 테스트**(`SecurityChainIntegrationTest`)로 "미인증 `/api`는 401이고 리다이렉트가 아니다"를 고정해두었습니다.

재고 변경 경로가 `InventoryService.applyDelta` 한 곳으로 모이는 것도 같은 목적입니다 — 입고·조정·시드가 전부 한 지점을 지나므로 **원장 누락이 구조적으로 불가능**하고, 불변식(Σdelta == onHand)으로 검증됩니다.

## 초기 데이터

- **재고**: 기동 시 `InitDb`가 productId 1~20, onHandQty 15·30·…·300 으로 시드합니다(OMS `InitDb`의 상품 데이터와 수량 일치). 각 시드는 원장에 `OPENING` 행을 남깁니다.
- **관리자 계정**: `WmsUserSeeder`가 `operator`(OPERATOR)·`manager`(MANAGER)를 시드합니다. 비밀번호는 BCrypt로만 저장하며, 같은 username이 이미 있으면 건너뜁니다(멱등).

## 테스트

```bash
./gradlew test
```

- **도메인 단위** — `InventoryTest` / `ReservationTest` / `PurchaseOrderTest` / `ReplenishmentRequestTest` / `WmsUserTest`
  - 발주 상태 전이(부분 입고·취소), 취소된 발주의 입고 거부 포함
- **서비스 통합**(`@DataJpaTest`) — `InventoryServiceTest` / `PurchaseOrderServiceTest` / `ReplenishmentRequestServiceTest`
  - 원장 재구성 불변식(Σ delta == onHand), 발주 취소 시 연결 요청 종결·재고 불변, 목록 정렬 검증
- **MockMvc 슬라이스** — `InventoryControllerTest` / `ReplenishmentRequestControllerTest` / `WmsAdminControllerTest`
  - 롤 경계(OPERATOR가 MANAGER 액션 호출 시 403), 화면 렌더링(한글 상태·상품명·참조) 포함
- **보안 통합**(`@SpringBootTest(RANDOM_PORT)`) — `SecurityChainIntegrationTest`
  - `/api/**` 미인증 **401 유지**(302 아님), Basic 200, 폼 로그인 왕복. MockMvc가 재현 못 하는 실서블릿 `/error` 재디스패치를 검증
- **설정·기동** — `WmsUserSeederTest`(멱등·BCrypt·공백 자격증명 기동 실패) / `DbUserDetailsServiceTest`(롤→`ROLE_` 권한)

### 수동 검증

자동 테스트가 못 잡는 실제 통신·화면·장애 복구는 OMS 저장소의
[통합 수동 검증 기준본](https://github.com/twin10240/jhg-commerce-project/blob/master/docs/manual-verification-scenarios.md)으로 확인합니다.

## 로컬 로드밸런싱 데모 (docker-compose)

WMS 웹 티어를 3개 인스턴스로 수평 확장하고 Nginx로 분산하는 로컬 데모입니다.
**Railway 배포 경로와 무관** — `railway.json`은 `Dockerfile` 하나만 쓰므로 `docker-compose.yml`·`nginx/`는 무시됩니다.

```
  요청 → nginx(:8080) → wms1/wms2/wms3(:8081) → postgres(공유 DB)
                                              → redis(공유 세션 · 분산 락)
```

### 실행

```bash
docker compose up --build      # 6개 컨테이너: postgres, redis, wms1~3, nginx
# 접속: http://localhost:8080  (폼 로그인: operator/operator 또는 manager/manager)
docker compose down            # 정리
```

### 핵심 설계 — 수평 확장을 막는 상태(state) 2곳을 제거

| 병목 | 원인 | 해결 |
|------|------|------|
| 세션 기반 CSRF | Spring 기본 CSRF 토큰이 인스턴스별 세션 메모리에 저장 → 다른 인스턴스로 라우팅되면 폼 POST 403 | **Redis 공유 세션**(Spring Session Data Redis) — `SecurityConfig` 무수정, 세션이 공유되니 CSRF 토큰도 공유 |
| 초기화 경합 | 다중 인스턴스 동시 기동 시 `InitDb`가 빈 DB에 동시 시딩 → `product_id` UNIQUE 충돌 | **Redisson 분산 락**(`wms:init-lock`) — 락 잡은 1개만 시딩 |

- **프로파일 게이팅**: Redis 세션·분산 락은 `scale` 프로파일에서만 활성(`SPRING_PROFILES_ACTIVE=prod,scale`).
  Railway `prod` 단독은 세션 자동구성과 `RedissonConfig`가 비활성이라 Redis 없이 동작한다.
- **로드밸런싱**: `nginx/nginx.conf`의 `upstream` 블록. 현재 `least_conn`(활성 연결 최소 인스턴스 우선).
  기본값은 라운드로빈이며, `least_conn` 한 줄 제거 시 RR로 전환.

### 검증

```bash
# ① 로드밸런싱 — 매 요청 처리 인스턴스를 X-Served-By 헤더로 확인
curl -s -u wms:wms -D - -o /dev/null http://localhost:8080/api/inventory/rows | grep -i X-Served-By

# ② 분산 락 — 정확히 1개 인스턴스만 시딩, 나머지는 skip
docker compose logs wms1 wms2 wms3 | grep -E "시드 완료|시딩 skip"

# ③ 공유 세션 — 세션이 Redis에 저장됐는지
docker compose exec redis redis-cli --scan --pattern "spring:session:*"
```

`X-Served-By`는 nginx가 `$upstream_addr`(요청을 넘긴 백엔드)를 응답 헤더에 찍은 것으로, 분산 동작의 증거입니다.

## 문서

- [OMS·WMS 통합 수동 검증](https://github.com/twin10240/jhg-commerce-project/blob/master/docs/manual-verification-scenarios.md)
- [`docs/wms-business-roadmap.md`](docs/wms-business-roadmap.md) — 완료 기능과 1차 이후 선택 로드맵
- [`docs/wms-admin-ux-followup.md`](docs/wms-admin-ux-followup.md) — 관리자 UX 완료·잔여 항목
- [`docs/superpowers/`](docs/superpowers/) — 날짜별 설계·구현 계획 기록
