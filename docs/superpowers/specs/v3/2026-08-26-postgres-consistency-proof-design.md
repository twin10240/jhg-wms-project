# PostgreSQL 통일 + 정합성 증명 — WMS V3.0 스펙

## 왜 지금 이 작업인가

이 시스템의 핵심 주장은 세 가지다. **오버셀을 막는다, 원장 불변식(Σdelta == onHand)이 유지된다,
상대가 죽어도 재고가 오염되지 않는다.** 셋 다 README 전면에 있다.

그런데 증거가 주장을 못 따라간다.

- **동시성 테스트에 실제 스레드가 하나도 없다.** `reserveAll_경합시_부분예약_없이_전체_롤백한다`라는
  이름의 테스트조차 순차 실행이다. `Inventory`의 `@Version` 낙관적 락과
  `ReservationRepository.findByOrderIdWithLock`의 비관적 락은 한 번도 동시 요청으로 검증된 적이 없다.
- **불변식은 문서에만 있다.** 런타임에도 테스트에도 이를 확인하는 코드가 없다.
- **회복탄력성은 수동 관측 한 번이 전부다.** `read-timeout: 2s` 설정이 실제로 끊는지 증거가 없다.
- **증명 기반이 운영과 다르다.** 테스트·개발은 H2, 운영은 PostgreSQL이다. 낙관적 락은 DB 독립이라
  무방하지만 `FOR UPDATE`와 격리 수준은 엔진이 직접 구현하는 부분이다.

마지막 항목은 이미 두 번 물렸던 곳이다. `docs/wms-enum-schema-migration.md`가 존재하는 이유가
그 간극이고, 그 문서는 "dev H2에서 `RETURN` 추가 후 RMA 재입고가 500으로 터진" 사고를 기록하고 있다.

한 문장으로 이 작업은 **"주장을 실행 가능한 증거로 바꾸는 것"** 이다. 기능은 거의 늘지 않는다.

## 범위

```
V3
├─ V3.0  ← 이 스펙
│   ├─ Phase 1  DB 통일 (dev = test = prod = PostgreSQL 16)
│   └─ Phase 2  정합성 증명 4종
└─ V3.1  위치·다중창고   ← 별도 스펙. V3.0 완료 후 재논의
```

순서의 근거: 동시성 테스트를 **서비스 API 수준**으로 쓰면 내부 모델이 `(상품)`에서
`(상품, 위치)`로 바뀌어도 계약은 그대로다. "가용 5개에 3개씩 두 요청이 동시에 오면 하나만 성공한다"는
위치가 생겨도 참이어야 한다. 먼저 만든 증명이 V3.1 모델 개조의 회귀 안전망이 된다.

반대 순서면 모델을 갈아엎은 뒤에 처음부터 증명해야 하고, 그때는 "원래 되던 게 깨진 것"과
"원래 안 됐던 것"을 구분할 수 없다.

---

# Phase 1 — DB 통일

## 목표

`dev = test = prod = PostgreSQL 16`. H2를 완전히 걷어낸다.

## 변경 대상

| 파일 | 변경 |
|---|---|
| `src/main/resources/application.yml` | 기본 datasource를 Postgres로. `local` 프로파일의 `ddl-auto: create` 유지. `spring.h2.console` 삭제 |
| `src/test/resources/application.yml` | `jdbc:h2:mem:` → Postgres. `ddl-auto: create-drop`은 컨텍스트당 1회 |
| `docker-compose.yml` | `postgres` 서비스에 `ports: ["5432:5432"]` 추가 — 현재 노출이 없어 로컬에서 붙을 수 없다 |
| `.github/workflows/ci.yml` | `services:` 블록에 `postgres:16-alpine` 추가 |
| `build.gradle` | `runtimeOnly 'com.h2database:h2'` 삭제 |
| `README.md` | 로컬 실행 전제 조건에 `docker compose up -d postgres` 명시 |

`org.postgresql:postgresql`은 이미 `runtimeOnly`로 들어 있다. **Gradle 의존성은 늘지 않는다.**

## Testcontainers를 쓰지 않는 이유

GitHub Actions의 `services:` 블록이 컨테이너를 붙여준다. CI에는 이미 Docker가 있다(이미지 빌드 잡이
돌고 있다). 로컬은 `docker compose up -d postgres` 하나면 된다 — 전체 데모 스택(nginx + WMS 3대 + redis)을
띄울 필요 없이 그 서비스만 올라온다.

두 경로 모두 이미 있는 것으로 되므로 의존성을 하나 더 얹을 근거가 없다.

## 완료 조건과 게이트

**완료 조건**: 278건이 PostgreSQL에서 전부 통과. H2 의존성·설정 잔재 0.

**게이트**: 전환 직후 테스트 시간을 측정한다. 인메모리에서 실제 엔진으로 가면 느려지는데
얼마나인지는 재봐야 안다. 기준선은 다음과 같다.

- **로컬 1분 이내 / CI 3분 이내** → Phase 2로 진행
- **초과** → 멈추고 재논의. 스키마 생성을 컨텍스트당 1회로 고정하거나, 느린 슬라이스를 골라내는
  완화책을 먼저 검토한다

숫자를 재지 않고 "괜찮겠지"로 넘어가면 PR마다 기다리는 비용으로 돌아온다. 현재 기준선은
로컬 9초, CI 1분 5초다.

## enum 마이그레이션 문서의 위치

Phase 1이 `docs/wms-enum-schema-migration.md`를 없애지는 않는다. 그 문제는 `ddl-auto: update`가
기존 컬럼의 check 제약을 갱신하지 않는 **PostgreSQL 자체의 성질**이지 H2와의 차이가 아니다.

달라지는 것은 **발견 시점**이다. dev가 Postgres가 되면 enum 값을 추가했을 때 운영에 나가기 전
로컬에서 터진다. 문서와 스크립트는 그대로 유지한다.

---

# Phase 2 — 정합성 증명 4종

## 왜 하니스가 먼저인가

현재 테스트가 진짜 경합을 만들지 못하는 이유는 `@DataJpaTest`가 **테스트 메서드 전체를 하나의
트랜잭션으로 감싸고 끝나면 롤백**하기 때문이다. 스레드를 띄워도 그 스레드는 별도 커넥션을 쓰므로
아직 커밋되지 않은 시드 데이터를 보지 못한다. 경합이 아니라 그냥 실패한다.

필요한 것은 트랜잭션 경계가 진짜인 하니스다.

```java
@SpringBootTest                      // 테스트 트랜잭션 없음
class InventoryConcurrencyTest {

    @Autowired TransactionTemplate tx;   // 각 스레드가 자기 트랜잭션을 연다

    /** N개 스레드를 같은 순간에 출발시키고 성공·실패를 모은다. */
    private RaceResult race(int threads, IntConsumer task) { ... }
}
```

`race(n, task)` 하나가 증명 1과 4를 함께 떠받친다. `CountDownLatch` 출발 게이트로 진짜 동시 시작을
보장하고, 각 스레드의 성공과 예외를 집계한다. `@Version` 충돌은
`ObjectOptimisticLockingFailureException`으로 나오므로 그것도 "실패"로 센다.

롤백이 없으므로 뒷정리가 필요하다. `@AfterEach`에서 테이블을 비우고, 그 김에 불변식 검증도 함께 건다.

## 증명 1 — 오버셀 방지

| 시나리오 | 단언 |
|---|---|
| 재고 5, 두 요청이 각 3개 예약 | 성공 정확히 1건, `reservedQty == 3`, `availableQty == 2` |
| 재고 10, 다섯 요청이 각 3개 예약 | 성공 최대 3건, `reservedQty <= 10` |
| 같은 예약을 두 스레드가 동시 출고 | `onHand` 이중 차감 없음 |

핵심 단언은 타이밍이 아니라 불변 조건이다.

```
Σ(성공 건수 × 요청 수량) ≤ onHand
```

몇 건이 성공하느냐는 스케줄링에 따라 흔들려도 오버셀이 없다는 것은 항상 참이어야 한다.
이렇게 써야 플레이키하지 않다.

## 증명 2 — 원장 불변식

별도 테스트가 아니라 **모든 동시성 시나리오에 자동으로 걸리는 후크**로 둔다.

```java
@AfterEach
void 불변식이_유지된다() {
    // 상품별로 Σdelta == onHandQty
}
```

시나리오가 하나 늘 때마다 검증이 따라온다. 별도 테스트로 두면 "그 테스트에서만" 참인 것이 된다.

### 수불대장 화면 표시

`InventoryService.buildLedger`가 이미 원장에서 기말재고를 집계한다. 그 값과 실제 `onHandQty`를
비교해 불일치가 있으면 화면에 경고를 띄운다. 계산은 이미 하고 있으므로 추가 조회가 거의 없다.

문서에만 있던 주장이 화면에서 보이게 되는 것이라 서사로도 값이 있다.
경고 문구는 상품과 두 값의 차이를 함께 보여준다.

## 증명 3 — 회복탄력성

동시성 하니스와 무관한 별도 축이다. 새 의존성 없이 간다.

| 상황 | 만드는 법 | 단언 |
|---|---|---|
| OMS가 죽음 | `oms.base-url`을 사용 중이지 않은 포트로 | 입고·조정은 커밋, 통지만 실패 |
| OMS가 느림(hang) | JDK 내장 `com.sun.net.httpserver.HttpServer`로 3초 지연 응답 | `read-timeout: 2s`가 실제로 끊음. 재고는 정상 커밋 |
| OMS가 401 응답 | 같은 내장 서버로 401 리턴 | `error` 로그, 재고는 정상 커밋 |

두 번째가 가장 값어치 있다. `spring.http.client.read-timeout: 2s`는 설정만 있고 동작 증거가 없다.

WireMock을 쓰지 않는다 — JDK 내장 HTTP 서버로 충분하고 의존성이 늘지 않는다.

## 증명 4 — 실사·조정 경합

| 시나리오 | 예상 |
|---|---|
| 같은 세션을 두 관리자가 동시 승인 | 하나만 반영, `COUNT` 원장 1행 |
| 승인과 수동 조정이 동시 | 조정은 `assertAdjustable` 가드로 거부 |
| **같은 상품으로 두 세션 동시 개설** | **깨진다 — 재현 후 수정한다** |

세 번째는 `CycleCountService.java`의 `ponytail:` 주석에 이미 적힌 알려진 공백이다.
`findOpenProductIds()` 겹침 검사와 세션 생성 사이에 락이 없다.

### 수정 방식 — 비관적 락으로 개설을 직렬화

`open()`에서 대상 `Inventory` 행들을 `SELECT ... FOR UPDATE`로 잡고 겹침 검사 후 생성한다.
같은 상품을 노리는 두 요청이 자연히 줄을 선다.
`ReservationRepository.findByOrderIdWithLock`이 이미 쓰는 패턴이라 새 개념이 없다.

**부분 유니크 인덱스는 채택하지 않는다.** 코드 주석에 그 방안이 적혀 있으나 성립하지 않는다 —
조건이 `cycle_count.status IN (OPEN, SUBMITTED)`인데 인덱스는 `cycle_count_item`에 걸어야 하고,
부분 인덱스의 `WHERE` 절은 다른 테이블의 컬럼을 참조할 수 없다. 주석도 함께 정정한다.

**데드락 방지**: 대상 상품을 `productId` 오름차순으로 정렬해 잠근다. 두 요청이 겹치는 상품 집합을
서로 다른 순서로 잠그면 데드락이 생긴다.

Phase 1이 이 수정을 뒷받침한다. dev·test가 H2였다면 `FOR UPDATE` 동작이 운영과 달라 "고쳤다"는
확신이 서지 않았을 것이다. 같은 엔진에서 증명된다.

## 플레이키 방지 규칙

CI에서 매번 도는 이상 이것이 설계의 절반이다. 한 번이라도 랜덤 실패하면 신뢰가 무너지고
결국 `@Disabled`로 간다.

- 스레드 수는 작게 유지한다(2~5). 많을수록 비결정적이고 얻는 것이 없다
- 단언은 불변 조건으로 쓴다. "정확히 2건 성공"이 아니라 "성공분의 합이 재고를 넘지 않는다"
- 모든 `race()`에 타임아웃을 건다. hang이면 매달리지 않고 실패해야 한다
- 시드는 시나리오별로 독립적인 상품 ID를 쓴다. 서로 간섭하지 않게 한다

## 파일 배치

```
src/test/java/com/jhg/wms/concurrency/
    ConcurrencySupport.java          race() 하니스 + 불변식 후크
    InventoryConcurrencyTest.java    증명 1
    CycleCountConcurrencyTest.java   증명 4
src/test/java/com/jhg/wms/resilience/
    OmsFailureTest.java              증명 3 (JDK 내장 HttpServer)
```

기존 테스트는 건드리지 않는다. `@DataJpaTest` 슬라이스는 빠르고 잘 돌고 있으므로 그대로 둔다.

---

# 완료 조건

| Phase | 조건 |
|---|---|
| 1 | 278건이 PostgreSQL에서 전부 통과. H2 잔재 0. 로컬 1분 / CI 3분 이내 |
| 2 | 증명 4종 CI 통과. 실사 겹침 경합은 재현 → 수정 → 재발 방지까지 |
| 공통 | 불변식 후크가 모든 동시성 시나리오에 자동 적용. 수불대장 화면에 불변식 상태 표시 |

# 리스크

| 리스크 | 완화 |
|---|---|
| 테스트 속도 저하 | Phase 1 게이트에서 측정 후 판단. 초과 시 진행하지 않는다 |
| 플레이키 테스트 | 위 규칙을 필수로 적용. 불변 조건 단언, 작은 스레드 수, 타임아웃 |
| 로컬 진입 장벽 | 도커 없이 테스트 불가. README에 전제 조건 명시 |
| 겹침 수정이 데드락 유발 | 상품 ID 정렬 순서로 잠근다 |

# 비범위

- 위치·다중창고 — V3.1
- 부하 테스트 도구(k6, Gatling) — 목표는 CI 회귀지 성능 수치가 아니다
- Testcontainers — Actions `services:`로 충분하다
- 운영 중 불변식 주기 검사(스케줄러·알림) — 화면 표시까지만. 실제로 깨진 적이 있을 때 검토한다
- 기존 `@DataJpaTest` 슬라이스의 재작성
