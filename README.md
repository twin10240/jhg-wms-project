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

## API

### 재고 조회

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/inventory/availability?productIds=1,2,3` | 가용수량 맵 반환 (OMS 채널1 연동) |
| GET | `/api/inventory/rows` | 전체 재고 목록 (관리자) |

### 재고 쓰기

| Method | URL | Body | 설명 |
|--------|-----|------|------|
| POST | `/api/inventory/adjust` | `{"productId":1,"delta":10}` | 수동 재고 조정 (±) |
| POST | `/api/inventory/reserve` | `{"orderId":1,"items":{"1":3,"2":1}}` | 예약 (멱등) |
| POST | `/api/inventory/ship` | `{"orderId":1,"items":{"1":3,"2":1}}` | 출고 |
| POST | `/api/inventory/release` | `{"orderId":1,"items":{"1":3,"2":1}}` | 예약 해제 |

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

- `InventoryTest` — 도메인 단위 테스트
- `ReservationTest` — Reservation 상태 전이
- `InventoryServiceTest` — 서비스 레이어 통합 테스트
- `InventoryControllerTest` — MockMvc 슬라이스 테스트
