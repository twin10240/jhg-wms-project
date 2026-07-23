# JHG-WMS

OMS(주문관리시스템)와 연동하는 창고관리시스템(Warehouse Management System) 학습 프로젝트입니다.

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

## 운영 배포 (Railway)

- Dockerfile(멀티스테이지 JDK21) 존재 시 Railway가 자동 사용. `.dockerignore`로 build/·.git/ 제외.
- `prod` 프로파일: PostgreSQL(PG* 변수), `ddl-auto: update`, H2 콘솔 off. 빈 DB면 `InitDb`가 재고 1~20 시드.
- Variables: `SPRING_PROFILES_ACTIVE=prod`, `PORT=8081`(private networking 주소 고정용), `OMS_BASE_URL=http://<oms>.railway.internal:8080`, `WMS_BASIC_USER`/`WMS_BASIC_PASSWORD`(Basic Auth 자격증명 — OMS 서비스에도 동일 값 필수).
- 공개 도메인: `https://jhg-wms-project-production.up.railway.app` (Basic Auth 필수). OMS는 여전히 private networking(`WMS_BASE_URL`)으로 호출.
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

### OMS 재고보충 통지 (S3, 채널3)

재고가 늘어나면(발주 입고, +조정) `OmsReplenishmentNotifier`가 트랜잭션 커밋 후 OMS `POST /api/replenishments` 에 `{"productIds":[...]}` 를 보냅니다 — OMS가 백오더를 FIFO 승격.

- 발화점은 `InventoryService.applyDelta` 한 곳 — 모든 재고 증가 경로(입고·WMS UI 조정)가 통과
- best-effort: OMS가 다운이어도 입고/조정은 성공, warn 로그만 남김 (누락 승격은 S4 보상 스윕이 커버)
- 통지는 자연 멱등(사실 전달뿐) — 중복 수신 시 OMS 쪽 no-op
- 콜백 대상: `oms.base-url` (기본 `http://localhost:8080`)
- 통지·전 REST 응답에 타임아웃(connect 1s / read 2s, `spring.http.client.*`) — OMS hang이어도 최대 수 초 내 복귀 (S4)
- `shipAll`은 RELEASED 예약 출고를, `releaseAll`은 SHIPPED 예약 해제를 거부(S4) — 타임아웃 반쪽 상태에서의 재고 오염(reservedQty 음수) 방지

### 관리자 UI (Thymeleaf)

| URL | 설명 |
|-----|------|
| `/` | 대시보드 — 재고·발주·예약 요약 |
| `/admin/inventory` | 재고 조회(보유·예약·가용)·수동 조정·재고 트랜잭션 이력(OPENING/RECEIVE/SHIP/ADJUST, type 필터) |
| `/admin/reservations` | 예약 현황 조회 (상태 필터, 조회 전용) |
| `/admin/purchase-orders` | 발주 목록 조회(`ORDERED`/`PARTIALLY_RECEIVED`/`RECEIVED` 상태 필터)·생성(다품목) |
| `/admin/purchase-orders/{poId}` | 발주 상세 — 품목별 발주량·입고량·잔량 확인, 입고 처리(여러 번 나눠 입고 가능) |
| `/admin/replenishment-requests` | OMS 보충 요청 검토·승인·거절과 이력 조회 |

> 관리자 UI를 포함한 전 경로(`/`, `/admin/**`, `/api/**`)가 **HTTP Basic 인증**을 요구합니다(`WMS_BASIC_USER`/`WMS_BASIC_PASSWORD`, 로컬 기본 wms/wms). admin 폼 POST는 CSRF 토큰 필수, `/api/**`는 서버간 호출용으로 CSRF 예외. 이 전제로 공개 도메인이 붙어 있습니다 — 자격증명 변경 시 OMS 쪽 변수도 함께 바꿀 것(안 그러면 OMS→WMS 전면 401).

### 예약 멱등성

`Reservation` 엔티티가 `orderId`에 `UNIQUE` 제약을 가집니다.  
동일 `orderId`로 재요청 시 현재 상태(`RESERVED/SHIPPED/RELEASED`)를 그대로 반환합니다.

## 초기 데이터

기동 시 `InitDb`가 productId 1~20, onHandQty 15·30·…·300 으로 시드합니다.  
OMS `InitDb`의 상품 데이터와 수량이 일치합니다.

## 테스트

```bash
./gradlew test
```

- `InventoryTest` / `ReservationTest` / `PurchaseOrderTest` — 도메인 단위 테스트
- `InventoryServiceTest` / `PurchaseOrderServiceTest` — 서비스 레이어 통합 테스트
  - `InventoryServiceTest`에 원장 재구성 불변식(Σ delta == onHand) 검증 포함
- `InventoryControllerTest` / `ReplenishmentRequestControllerTest` — MockMvc 슬라이스 테스트

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
# 접속: http://localhost:8080  (Basic Auth: wms / wms)
docker compose down            # 정리
```

### 핵심 설계 — 수평 확장을 막는 상태(state) 2곳을 제거

| 병목 | 원인 | 해결 |
|------|------|------|
| 세션 기반 CSRF | Spring 기본 CSRF 토큰이 인스턴스별 세션 메모리에 저장 → 다른 인스턴스로 라우팅되면 폼 POST 403 | **Redis 공유 세션**(Spring Session Data Redis) — `SecurityConfig` 무수정, 세션이 공유되니 CSRF 토큰도 공유 |
| 초기화 경합 | 다중 인스턴스 동시 기동 시 `InitDb`가 빈 DB에 동시 시딩 → `product_id` UNIQUE 충돌 | **Redisson 분산 락**(`wms:init-lock`) — 락 잡은 1개만 시딩 |

- **프로파일 게이팅**: Redis 세션·분산 락은 `scale` 프로파일에서만 활성(`SPRING_PROFILES_ACTIVE=prod,scale`).
  Railway(prod 단독)는 `session.store-type=none` + `RedissonConfig` 비활성이라 **Redis 없이 그대로 동작**.
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
