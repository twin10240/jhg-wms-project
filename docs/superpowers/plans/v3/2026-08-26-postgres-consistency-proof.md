# PostgreSQL 통일 + 정합성 증명 구현 계획 (WMS V3.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dev·test·prod를 PostgreSQL로 통일하고(로컬·CI는 17), 그 위에서 오버셀 방지·원장 불변식·회복탄력성·실사 경합을 실제 동시 요청으로 증명한다.

**Architecture:** Phase 1은 설정 변경만으로 H2를 걷어내고 테스트를 실제 Postgres로 옮긴다(게이트: 로컬 1분 / CI 3분). Phase 2는 `@SpringBootTest` 기반 동시성 하니스(`race()`)를 만들어 진짜 트랜잭션 경계에서 경합을 재현하고, 불변식은 `@AfterEach` 후크로 모든 시나리오에 자동 적용한다. 실사 겹침 경합은 재현 후 비관적 락으로 직렬화해 고친다.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Data JPA, PostgreSQL(로컬·CI 17), Thymeleaf, JUnit 5 + AssertJ + Mockito, Gradle, GitHub Actions.

## Global Constraints

- 스펙: `docs/superpowers/specs/v3/2026-08-26-postgres-consistency-proof-design.md`
- 브랜치: `feat/wms-v3.0` (이미 생성됨, 스펙 커밋 `1bb005d`)
- 빌드/테스트: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
- 테스트 전 로컬 PostgreSQL 17이 떠 있어야 한다: `brew services start postgresql@17`.
  이 머신에는 Docker가 없다 — docker-compose는 수평 확장 데모 전용이며 이 계획에서 쓰지 않는다.
- 시작 시점 테스트 278건 전부 통과. 각 태스크 종료 시 전체 그린 유지.
- **새 Gradle 의존성을 추가하지 않는다.** Testcontainers·WireMock 금지. `org.postgresql:postgresql`은 이미 있다.
- 동시성 단언은 타이밍이 아니라 불변 조건으로 쓴다. 스레드 수는 2~5.
- 동시성 테스트는 `productId >= 9000`, `orderId >= 9000`만 쓴다. (Task 2 이후 `InitDb` 시딩은
  테스트에서 꺼져 있어 1~20이 없지만, 캐시된 `@DataJpaTest` 컨텍스트가 1·2를 고정으로 쓰므로 구간을 분리한다.)
- **테스트 DB 사실 (Task 2에서 확인됨. 이 전제 위에서 설계한다):**
  - `InitDb`는 `wms.init-db.enabled: false`로 테스트에서 꺼져 있다. 목킹할 필요 없고, 시드 1~20도 없다.
  - `@DataJpaTest`의 datasource 치환은 `spring.test.database.replace: none`으로 전역 해제돼 있다.
  - `ddl-auto: create-drop`이라 **새 스프링 컨텍스트가 뜰 때마다 `wms_test` 스키마가 통째로 재생성된다.**
    커밋한 행은 클래스 안에서는 살아 있지만 컨텍스트 경계를 넘어 살아남는다고 가정하면 안 된다.
  - `@GeneratedValue`는 시퀀스(allocationSize 50, pooled)다. 컨텍스트가 뜰 때마다 시퀀스가 1로 리셋되는데
    캐시된 컨텍스트는 메모리 할당분을 들고 있다. **행을 커밋하는 테스트에서 PK 충돌로 드러날 수 있다** —
    발생하면 우회하지 말고 보고한다.
- 화면에 enum 원문을 노출하지 않는다.
- 커밋 메시지는 한국어, 본문에 "왜"를 적는다. 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

# Phase 1 — DB 통일

### Task 1: 테스트를 PostgreSQL로 옮기고 속도를 측정한다

**Files:**
- Modify: `src/test/resources/application.yml:7-16`

**Interfaces:**
- Produces: 테스트용 데이터베이스 `wms_test` (dev용 `wms`와 분리 — `create-drop`이 개발 데이터를 지우지 않게)
- Produces: 로컬 Postgres 접속점 `localhost:5432`, 롤 `wms` / 비밀번호 `wms`

- [ ] **Step 1: 로컬 PostgreSQL이 준비됐는지 확인한다**

이 머신에는 Docker가 없다. Homebrew의 `postgresql@17`을 쓴다.
통제자가 사전에 기동과 DB 생성을 마쳐두었으므로 여기서는 확인만 한다.

```bash
psql -h localhost -p 5432 -U wms -d wms_test -c "SELECT current_database(), version();"
```

Expected: `wms_test`와 PostgreSQL 17.x 버전 문자열이 출력된다.

실패하면 **BLOCKED로 보고한다.** 직접 `brew services`를 조작하지 않는다 — 호스트 수준 변경이다.

- [ ] **Step 2: (해당 없음 — 통제자가 환경을 준비했다)**

- [ ] **Step 3: (해당 없음 — 통제자가 환경을 준비했다)**

- [ ] **Step 4: 테스트 데이터소스를 Postgres로 바꾼다**

`src/test/resources/application.yml`에서 아래 블록을 교체한다.

기존:

```yaml
spring:
  # 테스트는 임베디드 H2(create-drop) — TCP 서버 불필요, 실 DB 오염 없음.
  datasource:
    url: jdbc:h2:mem:jhg-wms-test;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        default_batch_fetch_size: 100
  h2:
    console:
      enabled: false
```

교체 후:

```yaml
spring:
  # 테스트도 운영과 같은 엔진(PostgreSQL)을 쓴다 — FOR UPDATE·격리 수준은
  # 엔진이 직접 구현하는 부분이라 H2로는 운영 동작을 증명할 수 없다.
  # 전제: 로컬 Homebrew postgresql@17이 기동 중이고 wms/wms_test가 준비돼 있음.
  datasource:
    url: jdbc:postgresql://localhost:5432/wms_test
    driver-class-name: org.postgresql.Driver
    username: wms
    password: wms
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

- [ ] **Step 5: 전체 테스트를 돌리고 시간을 잰다**

```bash
time JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, 278건 통과.

집계 확인:

```bash
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t, "skipped="s, "failures="f, "errors="e}'
```

Expected: `tests=278 skipped=0 failures=0 errors=0`

- [ ] **Step 6: 게이트 판정**

`time` 출력의 `real` 값을 기준선과 비교한다. 전환 전 로컬 기준선은 **9초**다.

- **1분 이내** → Task 2로 진행
- **1분 초과** → **여기서 멈춘다.** 측정값을 사용자에게 보고하고 완화책(스키마 생성을 컨텍스트당 1회로 고정, 느린 슬라이스 분리)을 논의한 뒤 재개한다. 임의로 진행하지 않는다.

- [ ] **Step 7: 커밋**

```bash
git add src/test/resources/application.yml
git commit -m "$(cat <<'EOF'
test(wms): 테스트 DB를 H2 인메모리에서 PostgreSQL로 교체

FOR UPDATE와 격리 수준은 DB 엔진이 직접 구현하는 부분이라, 운영이 Postgres인데
H2로 증명하면 동시성 주장이 운영 보증이 되지 않는다. 정합성 증명(Phase 2)의 전제다.

테스트 전용 DB wms_test를 dev(wms)와 분리한다 — 같은 DB를 쓰면
ddl-auto: create-drop이 개발 데이터를 매 실행마다 지운다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 개발·CI·의존성에서 H2를 걷어낸다

**Files:**
- Modify: `src/main/resources/application.yml:5-24`
- Modify: `build.gradle:33`
- Modify: `.github/workflows/ci.yml:10-14`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1의 `wms_test` DB와 로컬 5432 포트
- Produces: `dev = test = prod = PostgreSQL`. classpath에 H2 없음

- [ ] **Step 1: 개발 데이터소스를 Postgres로 바꾼다**

`src/main/resources/application.yml`의 상단 블록을 교체한다.

기존:

```yaml
  # WMS 자체 DB(OMS와 물리 분리). OMS는 ~/hgpage, WMS는 ~/jhg-wms 로 별도 파일.
  datasource:
    url: jdbc:h2:tcp://localhost/~/jhg-wms
    driver-class-name: org.h2.Driver
    username: sa
    password:
```

교체 후:

```yaml
  # WMS 자체 DB(OMS와 물리 분리). 개발·테스트·운영 모두 PostgreSQL.
  # 전제(로컬): brew services start postgresql@17 — 로컬 개발·테스트는 Docker를 쓰지 않는다(운영 배포는 Dockerfile 사용).
  datasource:
    url: jdbc:postgresql://localhost:5432/wms
    driver-class-name: org.postgresql.Driver
    username: wms
    password: wms
```

같은 파일에서 아래 블록을 **삭제**한다:

```yaml
  h2:
    console:
      enabled: true
```

`prod` 프로파일 안의 아래 블록도 **삭제**한다(H2가 없으니 끌 것도 없다):

```yaml
  h2:
    console:
      enabled: false
```

`local` 프로파일(`ddl-auto: create`)은 그대로 둔다.

- [ ] **Step 2: H2 의존성을 삭제한다**

`build.gradle`에서 아래 줄을 삭제한다:

```groovy
	runtimeOnly 'com.h2database:h2'
```

같은 줄 아래 Postgres 의존성의 주석도 정정한다.

기존:

```groovy
	runtimeOnly 'org.postgresql:postgresql'   // prod(Railway Postgres) 전용 — H2와 공존
```

교체 후:

```groovy
	runtimeOnly 'org.postgresql:postgresql'   // dev·test·prod 공통 — H2는 V3.0에서 제거됨
```

- [ ] **Step 3: 컴파일과 테스트가 여전히 통과하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 278건 통과. H2 클래스를 참조하는 코드가 있었다면 여기서 컴파일 에러로 드러난다.

- [ ] **Step 4: CI에 Postgres 서비스를 붙인다**

`.github/workflows/ci.yml`의 `build-and-test` 잡에서 `runs-on` 바로 아래에 `services` 블록을 추가한다.

기존:

```yaml
  build-and-test:
    name: Build & Test (JDK 21)
    runs-on: ubuntu-latest
    steps:
```

교체 후:

```yaml
  build-and-test:
    name: Build & Test (JDK 21)
    runs-on: ubuntu-latest

    # 테스트가 운영과 같은 엔진을 쓴다. Actions가 컨테이너를 붙여주므로
    # Testcontainers 같은 Gradle 의존성이 필요 없다.
    services:
      postgres:
        image: postgres:17-alpine
        env:
          POSTGRES_DB: wms_test
          POSTGRES_USER: wms
          POSTGRES_PASSWORD: wms
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -U wms -d wms_test"
          --health-interval 5s
          --health-timeout 3s
          --health-retries 10

    steps:
```

같은 파일에서 아래 주석을 정정한다.

기존:

```yaml
      # build = 테스트(임베디드 H2) + bootJar 조립. 배포 산출물이 실제로 만들어지는지까지 검증.
```

교체 후:

```yaml
      # build = 테스트(services의 PostgreSQL) + bootJar 조립. 배포 산출물이 실제로 만들어지는지까지 검증.
```

CI에서는 `POSTGRES_DB`를 바로 `wms_test`로 만들므로 init 스크립트가 필요 없다.

- [ ] **Step 5: README에 로컬 실행 전제 조건을 명시한다**

`README.md`의 `## 테스트` 섹션 첫 줄 앞에 아래를 추가한다.

```markdown
> **전제 조건**: 테스트는 실제 PostgreSQL 17에서 돕니다. 먼저 띄워주세요.
>
> ```bash
> brew services start postgresql@17
> ```
>
> 개발용은 `wms`, 테스트용은 `wms_test` 데이터베이스를 씁니다(테스트가 `create-drop`이라 분리).
> 최초 1회만 아래로 롤과 DB를 만듭니다.
>
> ```bash
> createuser -s wms 2>/dev/null; psql -d postgres -c "ALTER ROLE wms PASSWORD 'wms'"
> createdb -O wms wms; createdb -O wms wms_test
> ```
>
> `docker-compose.yml`은 수평 확장 데모 전용이며 테스트와 무관합니다.
```

- [ ] **Step 6: 전체 테스트 재확인 후 커밋**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
git add src/main/resources/application.yml build.gradle .github/workflows/ci.yml README.md
git commit -m "$(cat <<'EOF'
chore(wms): 개발·CI에서도 PostgreSQL 사용, H2 완전 제거

dev(H2)와 prod(Postgres)가 갈라져 있어 enum check 제약 사고를 두 번 겪었다
(docs/wms-enum-schema-migration.md). 엔진을 통일하면 그런 차이가 운영에 나가기 전
로컬에서 드러난다.

CI는 Actions services:로 컨테이너를 붙인다 — Gradle 의존성은 늘지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — 정합성 증명

### Task 3: 동시성 하니스 + 불변식 후크 + 오버셀 증명

**Files:**
- Create: `src/test/java/com/jhg/wms/concurrency/ConcurrencySupport.java`
- Create: `src/test/java/com/jhg/wms/concurrency/InventoryConcurrencyTest.java`

**Interfaces:**
- Produces: `ConcurrencySupport.race(int threads, IntPredicate task): RaceResult` — N개 스레드를 동시에 출발시키고 결과를 집계
- Produces: `ConcurrencySupport.RaceResult` — `succeeded(): int`, `failed(): int`, `errors(): List<Throwable>`
- Produces: `ConcurrencySupport.seedInventory(long productId, int onHand)` — 테스트 트랜잭션 밖에서 커밋
- Produces: `ConcurrencySupport.onHandOf(long productId): int`, `reservedOf(long productId): int`
- Produces: 상수 `PID_BASE = 9000L`, `ORDER_BASE = 9000L`
- Produces: `@AfterEach` 불변식 검증 + 정리 (하위 클래스가 자동 상속)

- [ ] **Step 1: 하니스를 작성한다**

`src/test/java/com/jhg/wms/concurrency/ConcurrencySupport.java` 생성:

```java
package com.jhg.wms.concurrency;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 트랜잭션 경계를 가진 동시성 테스트의 공통 기반.
 *
 * <p>@DataJpaTest는 테스트 메서드 전체를 하나의 트랜잭션으로 감싸고 끝나면 롤백한다.
 * 스레드를 띄워도 그 스레드는 별도 커넥션을 쓰므로 아직 커밋되지 않은 시드 데이터를 보지 못한다 —
 * 경합이 아니라 그냥 실패한다. 그래서 @SpringBootTest(테스트 트랜잭션 없음)를 쓰고,
 * 시드·정리를 TransactionTemplate으로 직접 커밋한다.
 *
 * <p>롤백이 없으므로 뒷정리가 필수다. 정리하지 않으면 캐시된 @DataJpaTest 컨텍스트가
 * 고정으로 쓰는 productId 1·2와 충돌한다. 테스트는 productId·orderId 모두 9000 이상만 쓴다.
 *
 * <p>InitDb 시딩은 테스트에서 꺼져 있으므로(wms.init-db.enabled=false) 목킹하지 않는다.
 */
@SpringBootTest
abstract class ConcurrencySupport {

    /** InitDb 시드(1~20)와 겹치지 않는 테스트 전용 구간. */
    protected static final long PID_BASE = 9000L;
    protected static final long ORDER_BASE = 9000L;

    /** race() 한 판이 이 시간을 넘기면 hang으로 보고 실패시킨다. */
    private static final long RACE_TIMEOUT_SECONDS = 10;

    @Autowired protected InventoryRepository inventoryRepository;
    @Autowired protected InventoryTransactionRepository transactionRepository;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected EntityManager em;

    /** 성공 건수와 실패 원인. 단언은 타이밍이 아니라 이 집계 위에 쓴다. */
    protected record RaceResult(int succeeded, int failed, List<Throwable> errors) {}

    /**
     * threads개의 스레드를 같은 순간에 출발시킨다.
     * task가 true를 반환하면 성공, false를 반환하거나 예외를 던지면 실패로 집계한다.
     * 낙관적 락 충돌(ObjectOptimisticLockingFailureException)도 실패로 잡힌다.
     */
    protected RaceResult race(int threads, IntPredicate task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.test(index) ? null : new IllegalStateException("task returned false");
                } catch (Throwable t) {
                    return t;
                }
            }));
        }

        start.countDown();   // 동시 출발

        int succeeded = 0;
        List<Throwable> errors = new ArrayList<>();
        try {
            for (Future<Throwable> f : futures) {
                Throwable t = f.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (t == null) succeeded++;
                else errors.add(t);
            }
        } catch (Exception e) {
            throw new AssertionError("race()가 " + RACE_TIMEOUT_SECONDS + "초 안에 끝나지 않았다(hang 의심)", e);
        } finally {
            pool.shutdownNow();
        }
        return new RaceResult(succeeded, errors.size(), errors);
    }

    /** 테스트 트랜잭션 밖에서 커밋한다 — 다른 스레드가 볼 수 있어야 경합이 성립한다. */
    protected void seedInventory(long productId, int onHand) {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(productId, "테스트상품 " + productId, onHand)));
    }

    protected int onHandOf(long productId) {
        return tx.execute(s -> inventoryRepository.findByProductId(productId).orElseThrow().getOnHandQty());
    }

    protected int reservedOf(long productId) {
        return tx.execute(s -> inventoryRepository.findByProductId(productId).orElseThrow().getReservedQty());
    }

    /**
     * 불변식은 별도 테스트가 아니라 후크로 둔다 — 시나리오가 하나 늘 때마다 검증이 따라온다.
     * 별도 테스트로 두면 "그 테스트에서만" 참인 것이 된다.
     * 검증을 먼저 하고 정리한다(정리가 먼저면 검증할 대상이 사라진다).
     */
    @AfterEach
    void 불변식을_확인하고_정리한다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.findAll().forEach(inv -> {
                    int sumDelta = transactionRepository.sumDeltaByProductId(inv.getProductId());
                    assertThat(sumDelta)
                            .as("Σdelta == onHand 위반 (productId=%d)", inv.getProductId())
                            .isEqualTo(inv.getOnHandQty());
                }));
        cleanUpTestRows();
    }

    /** 테스트가 만든 행을 지운다. 9000 이상 구간만 건드려 다른 클래스와 간섭하지 않는다. */
    private void cleanUpTestRows() {
        tx.executeWithoutResult(s -> {
            em.createQuery("DELETE FROM CycleCountItem i").executeUpdate();
            em.createQuery("DELETE FROM CycleCount c").executeUpdate();
            em.createNativeQuery("DELETE FROM reservation_item WHERE reservation_id IN "
                    + "(SELECT reservation_id FROM reservation WHERE order_id >= :base)")
                    .setParameter("base", ORDER_BASE).executeUpdate();
            em.createQuery("DELETE FROM Reservation r WHERE r.orderId >= :base")
                    .setParameter("base", ORDER_BASE).executeUpdate();
            em.createQuery("DELETE FROM InventoryTransaction t WHERE t.productId >= :base")
                    .setParameter("base", PID_BASE).executeUpdate();
            em.createQuery("DELETE FROM Inventory i WHERE i.productId >= :base")
                    .setParameter("base", PID_BASE).executeUpdate();
        });
    }
}
```

`CycleCount`는 `InitDb`가 만들지 않으므로 전량 삭제해도 안전하다. `reservation_item`은 `@ElementCollection`이라 JPQL 엔티티가 없어 네이티브 쿼리로 지운다.

- [ ] **Step 2: 오버셀 실패 테스트를 쓴다**

`src/test/java/com/jhg/wms/concurrency/InventoryConcurrencyTest.java` 생성:

```java
package com.jhg.wms.concurrency;

import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("동시 예약이 가용수량을 넘지 못한다 (오버셀 방지)")
class InventoryConcurrencyTest extends ConcurrencySupport {

    @Autowired InventoryService inventoryService;

    @Test
    void 가용_5에_3개씩_두_요청이_동시에_오면_하나만_성공한다() {
        long pid = PID_BASE + 1;
        seedInventory(pid, 5);

        RaceResult result = race(2, i ->
                inventoryService.reserveAll(ORDER_BASE + i, Map.of(pid, 3)));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(reservedOf(pid)).isEqualTo(3);
        assertThat(onHandOf(pid) - reservedOf(pid)).isEqualTo(2);   // 가용 2
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*InventoryConcurrencyTest*'
```

Expected: 이 시점에는 **통과할 수도, 실패할 수도 있다.** 통과하면 `@Version` 낙관적 락이 실제로 동작한다는 증거이고, 실패하면 오버셀이 재현된 것이다.

**어느 쪽이든 결과를 기록한다.** 실패했다면 그것이 발견이므로 사용자에게 보고하고 수정 방향을 논의한 뒤 진행한다. 통과했다면 Step 4로 간다.

- [ ] **Step 4: 나머지 오버셀 시나리오를 추가한다**

`InventoryConcurrencyTest`에 아래 두 테스트를 추가한다.

```java
    @Test
    void 가용_10에_3개씩_다섯_요청이_동시에_와도_예약은_10을_넘지_않는다() {
        long pid = PID_BASE + 2;
        seedInventory(pid, 10);

        RaceResult result = race(5, i ->
                inventoryService.reserveAll(ORDER_BASE + 10 + i, Map.of(pid, 3)));

        // 몇 건이 성공하느냐는 스케줄링에 따라 흔들린다. 불변 조건만 단언한다.
        assertThat(result.succeeded() * 3).isLessThanOrEqualTo(10);
        assertThat(reservedOf(pid)).isEqualTo(result.succeeded() * 3);
        assertThat(onHandOf(pid) - reservedOf(pid)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 같은_예약을_두_스레드가_동시에_출고해도_이중_차감되지_않는다() {
        long pid = PID_BASE + 3;
        long orderId = ORDER_BASE + 20;
        seedInventory(pid, 10);
        inventoryService.reserveAll(orderId, Map.of(pid, 4));

        race(2, i -> {
            inventoryService.shipAll(orderId, Map.of(pid, 4));
            return true;
        });

        assertThat(onHandOf(pid)).isEqualTo(6);    // 10 - 4, 한 번만 차감
        assertThat(reservedOf(pid)).isZero();
    }
```

세 번째는 `shipAll`이 `SHIPPED` 상태면 no-op이라는 계약을 동시 요청으로 확인한다. 두 스레드 모두 예외 없이 끝날 수도 있고 하나가 낙관적 락으로 튕길 수도 있는데, 어느 쪽이든 최종 수량은 하나만 반영된 값이어야 한다.

- [ ] **Step 5: 전체 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 281건 통과(278 + 3).

- [ ] **Step 6: 같은 테스트를 5회 연속 돌려 플레이키를 확인한다**

```bash
for i in 1 2 3 4 5; do
  JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
    ./gradlew test --tests '*InventoryConcurrencyTest*' --rerun-tasks -q || echo "FAILED on run $i"
done
```

Expected: 5회 모두 통과, `FAILED` 출력 없음. 한 번이라도 실패하면 단언이 타이밍에 기대고 있다는 뜻이므로 불변 조건으로 고쳐 쓴다.

- [ ] **Step 7: 커밋**

```bash
git add src/test/java/com/jhg/wms/concurrency/
git commit -m "$(cat <<'EOF'
test(wms): 동시성 하니스 + 오버셀 방지 증명

"오버셀을 막는다"가 README 전면에 있는데 증거가 순차 테스트뿐이었다.
@DataJpaTest는 테스트 전체를 한 트랜잭션으로 감싸 롤백하므로 스레드를 띄워도
다른 스레드가 시드를 보지 못한다 — 경합이 성립하지 않는다.

@SpringBootTest + TransactionTemplate으로 진짜 트랜잭션 경계를 만들고,
CountDownLatch 출발 게이트로 동시 시작을 보장하는 race() 하니스를 둔다.

단언은 타이밍이 아니라 불변 조건으로 쓴다 — 성공 건수는 스케줄링에 따라 흔들려도
Σ(성공×수량) ≤ onHand는 항상 참이어야 한다.

불변식(Σdelta == onHand)은 별도 테스트가 아니라 @AfterEach 후크로 둔다.
시나리오가 늘 때마다 검증이 따라오게 하기 위해서다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 수불대장 화면에 불변식 상태를 표시한다

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java` (`LedgerRow` 아래에 추가)
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java:109-124`
- Modify: `src/main/resources/templates/admin/inventory-ledger.html`
- Test: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: `InventoryService.buildLedger(LocalDate, LocalDate): List<LedgerRow>` (기존)
- Produces: `InventoryService.InvariantViolation(Long productId, String productName, int ledgerClosing, int actualOnHand)`
- Produces: `InventoryService.findInvariantViolations(List<LedgerRow> ledger): List<InvariantViolation>`

- [ ] **Step 1: 실패 테스트를 쓴다**

`src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`에 아래 두 테스트를 추가한다.

```java
    @Test
    void 수불대장_기간이_오늘까지면_불변식_일치를_표시한다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 10, 0, 5, 0, -3, 0, 0, 12)));
        when(inventoryService.findInvariantViolations(anyList())).thenReturn(List.of());

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("원장 합계와 실제 보유수량이 일치")));
    }

    @Test
    void 수불대장_불변식이_깨지면_상품과_차이를_보여준다() throws Exception {
        when(inventoryService.buildLedger(any(), any())).thenReturn(List.of(
                new InventoryService.LedgerRow(1L, "상품 1", 10, 0, 5, 0, -3, 0, 0, 12)));
        when(inventoryService.findInvariantViolations(anyList())).thenReturn(List.of(
                new InventoryService.InvariantViolation(1L, "상품 1", 12, 15)));

        mockMvc.perform(get("/admin/inventory/ledger").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("원장 합계와 실제 보유수량이 다릅니다")))
                .andExpect(content().string(containsString("상품 1")))
                .andExpect(content().string(containsString("12")))
                .andExpect(content().string(containsString("15")));
    }
```

`anyList()`는 `org.mockito.ArgumentMatchers.anyList`를 정적 임포트한다(같은 파일이 이미 `org.mockito.ArgumentMatchers.*` 계열을 쓰고 있다).

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*WmsAdminControllerTest*'
```

Expected: 컴파일 실패 — `findInvariantViolations`와 `InvariantViolation`이 없다.

- [ ] **Step 3: 서비스에 불변식 검사를 추가한다**

`InventoryService.java`의 `LedgerRow` record 선언 바로 아래에 추가한다:

```java
    /** 원장 합계(기말)와 실제 보유수량이 어긋난 상품. */
    public record InvariantViolation(Long productId, String productName,
                                     int ledgerClosing, int actualOnHand) {}

    /**
     * 원장에서 유도한 기말재고와 실제 onHand를 대조한다.
     * <p>불변식 Σdelta == onHand는 문서 주장이었을 뿐 어디서도 확인하지 않았다.
     * buildLedger가 이미 원장을 집계하므로 대조는 상품 목록 한 번 더 읽는 값이면 된다.
     * <p>주의: 기간의 끝이 오늘 이전이면 기말재고는 그 시점 값이라 현재 onHand와 다른 게 정상이다.
     * 호출 측이 기간을 확인하고 부른다.
     */
    public List<InvariantViolation> findInvariantViolations(List<LedgerRow> ledger) {
        Map<Long, Inventory> byId = inventoryRepository.findAll().stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i));
        List<InvariantViolation> violations = new ArrayList<>();
        for (LedgerRow row : ledger) {
            Inventory inv = byId.get(row.productId());
            if (inv == null) continue;   // 원장에만 있고 재고 행이 없는 상품 — 대조 대상 아님
            if (inv.getOnHandQty() != row.closing())
                violations.add(new InvariantViolation(
                        row.productId(), row.productName(), row.closing(), inv.getOnHandQty()));
        }
        return violations;
    }
```

- [ ] **Step 4: 컨트롤러에서 기간을 확인하고 호출한다**

`WmsAdminController.ledger`의 `try` 블록을 교체한다.

기존:

```java
        try {
            model.addAttribute("ledger", inventoryService.buildLedger(from, to));
        } catch (IllegalArgumentException e) {
            model.addAttribute("ledger", List.of());
            model.addAttribute("errorMessage", e.getMessage());
        }
```

교체 후:

```java
        try {
            List<InventoryService.LedgerRow> ledger = inventoryService.buildLedger(from, to);
            model.addAttribute("ledger", ledger);
            // 기간의 끝이 과거면 기말재고는 그 시점 값이라 현재 onHand와 달라야 정상이다.
            // 오늘까지 포함할 때만 불변식을 대조한다.
            boolean coversToday = !to.isBefore(LocalDate.now());
            model.addAttribute("invariantChecked", coversToday);
            model.addAttribute("invariantViolations",
                    coversToday ? inventoryService.findInvariantViolations(ledger) : List.of());
        } catch (IllegalArgumentException e) {
            model.addAttribute("ledger", List.of());
            model.addAttribute("invariantChecked", false);
            model.addAttribute("invariantViolations", List.of());
            model.addAttribute("errorMessage", e.getMessage());
        }
```

- [ ] **Step 5: 템플릿에 표시를 추가한다**

`src/main/resources/templates/admin/inventory-ledger.html`에서 `</table>` 다음 줄에 추가한다:

```html
  <!-- 원장 불변식(Σdelta == onHand)은 문서 주장이었을 뿐 화면에서 확인할 수 없었다.
       buildLedger가 이미 집계하는 값이라 대조 비용이 거의 없다. -->
  <div th:if="${invariantChecked}" style="margin-top:16px">
    <p th:if="${#lists.isEmpty(invariantViolations)}" class="hint">
      ✓ 원장 합계와 실제 보유수량이 일치합니다.
    </p>
    <div th:unless="${#lists.isEmpty(invariantViolations)}" role="alert"
         style="padding:12px; border:1px solid var(--color-danger); border-radius:var(--radius)">
      <strong>원장 합계와 실제 보유수량이 다릅니다.</strong>
      <table style="margin-top:8px">
        <thead><tr><th>상품</th><th>원장 기말</th><th>실제 보유</th><th>차이</th></tr></thead>
        <tbody>
          <tr th:each="v : ${invariantViolations}">
            <td th:text="${v.productName}">상품 1</td>
            <td style="text-align:right" th:text="${v.ledgerClosing}">12</td>
            <td style="text-align:right" th:text="${v.actualOnHand}">15</td>
            <td style="text-align:right" th:text="${v.actualOnHand - v.ledgerClosing}">3</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 283건 통과(281 + 2).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/InventoryService.java \
        src/main/java/com/jhg/wms/web/WmsAdminController.java \
        src/main/resources/templates/admin/inventory-ledger.html \
        src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 수불대장에 원장 불변식 대조 결과 표시

Σdelta == onHand는 README와 스펙의 핵심 주장인데 화면 어디서도 확인할 수 없었다.
buildLedger가 이미 원장에서 기말재고를 집계하므로, 실제 onHand와 대조하는 비용은
상품 목록 한 번 더 읽는 정도다.

기간의 끝이 과거면 기말재고는 그 시점 값이라 현재 onHand와 다른 게 정상이다.
오늘을 포함할 때만 대조한다 — 안 그러면 과거 조회마다 거짓 경고가 뜬다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 회복탄력성 증명 — OMS가 죽거나 느릴 때

**Files:**
- Create: `src/test/java/com/jhg/wms/resilience/OmsDownTest.java`
- Create: `src/test/java/com/jhg/wms/resilience/OmsSlowTest.java`

**Interfaces:**
- Consumes: `InventoryService.adjust(Long, int, String): int` (기존)
- Produces: 없음 (테스트 전용)

- [ ] **Step 1: OMS가 죽은 경우의 테스트를 쓴다**

`src/test/java/com/jhg/wms/resilience/OmsDownTest.java` 생성:

```java
package com.jhg.wms.resilience;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "상대가 죽어도 재고는 오염되지 않는다"의 증명.
 * 지금까지는 수동 관측 기록 한 번이 전부였다.
 *
 * <p>포트 1은 어떤 서비스도 듣지 않으므로 연결이 즉시 거부된다 — 죽은 OMS의 재현이다.
 * WireMock 같은 의존성을 쓰지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = "oms.base-url=http://localhost:1")
@DisplayName("OMS가 죽어도 재고와 원장은 커밋된다")
class OmsDownTest {

    private static final long PID = 9500L;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void 통지가_실패해도_조정은_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        int after = inventoryService.adjust(PID, 5, "OMS 다운 중 조정");

        assertThat(after).isEqualTo(15);
        assertThat(tx.execute(s -> inventoryRepository.findByProductId(PID).orElseThrow().getOnHandQty()))
                .isEqualTo(15);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> inventoryRepository.findByProductId(PID)
                .ifPresent(inventoryRepository::delete));
    }
}
```

`adjust`는 재고 증가라 `OmsReplenishmentNotifier.notifyAfterCommit`이 커밋 후 발화하고, 연결 거부는 notifier 내부 `catch (Exception e)`가 삼킨다. 조정 자체는 이미 커밋된 뒤다.

- [ ] **Step 2: OMS가 느린 경우의 테스트를 쓴다**

`src/test/java/com/jhg/wms/resilience/OmsSlowTest.java` 생성:

```java
package com.jhg.wms.resilience;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * read-timeout: 2s 가 실제로 끊는지의 증명.
 * 설정은 application.yml에 있었지만 동작 증거가 없었다.
 *
 * <p>느린 OMS는 JDK 내장 HttpServer로 만든다 — 새 의존성이 필요 없다.
 */
@SpringBootTest
@DisplayName("OMS가 느려도 타임아웃으로 끊고 재고는 커밋된다")
class OmsSlowTest {

    private static final long PID = 9501L;
    private static final int OMS_DELAY_MS = 3000;   // read-timeout 2s보다 길게

    private static HttpServer stub;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @DynamicPropertySource
    static void 느린_OMS를_띄운다(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            try {
                Thread.sleep(OMS_DELAY_MS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stub.start();
        registry.add("oms.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void 스텁을_내린다() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void 느린_OMS는_2초_타임아웃으로_끊기고_재고는_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        long startedAt = System.currentTimeMillis();
        int after = inventoryService.adjust(PID, 5, "OMS 지연 중 조정");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(after).isEqualTo(15);
        // 상한만 단언한다. "정확히 2초"는 타이밍 의존이라 플레이키하다.
        // 3초 응답을 끝까지 기다렸다면 이 선을 넘는다.
        assertThat(elapsed)
                .as("read-timeout 2s가 동작하지 않고 OMS 응답(%dms)을 끝까지 기다렸다", OMS_DELAY_MS)
                .isLessThan(OMS_DELAY_MS - 200);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> inventoryRepository.findByProductId(PID)
                .ifPresent(inventoryRepository::delete));
    }
}
```

- [ ] **Step 3: OMS가 401을 주는 경우의 테스트를 추가한다**

스펙의 세 번째 시나리오다. `OmsSlowTest`와 같은 방식이되 지연 없이 401을 돌려준다.
`src/test/java/com/jhg/wms/resilience/OmsUnauthorizedTest.java` 생성:

```java
package com.jhg.wms.resilience;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자격증명 오설정으로 OMS가 401을 돌려주는 상황.
 * 통지는 실패하지만 재고·원장은 커밋돼야 한다 — 인증 문제로 입고가 막히면 안 된다.
 */
@SpringBootTest
@DisplayName("OMS가 401을 줘도 재고와 원장은 커밋된다")
class OmsUnauthorizedTest {

    private static final long PID = 9502L;

    private static HttpServer stub;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @DynamicPropertySource
    static void 인증을_거부하는_OMS를_띄운다(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        stub.start();
        registry.add("oms.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void 스텁을_내린다() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void 통지가_401로_거부돼도_조정은_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        int after = inventoryService.adjust(PID, 5, "OMS 인증 실패 중 조정");

        assertThat(after).isEqualTo(15);
        assertThat(tx.execute(s -> inventoryRepository.findByProductId(PID).orElseThrow().getOnHandQty()))
                .isEqualTo(15);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> inventoryRepository.findByProductId(PID)
                .ifPresent(inventoryRepository::delete));
    }
}
```

`OmsReplenishmentNotifier`는 401을 `HttpClientErrorException.Unauthorized`로 잡아 `error` 로그를 남기고 삼킨다. 재고는 이미 커밋된 뒤다.

- [ ] **Step 4: 세 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*Oms*Test'
```

Expected: 3건 통과.

`OmsSlowTest`가 실패하면 `read-timeout: 2s`가 실제로 적용되지 않는다는 뜻이다. 그 경우 설정이 `RestClient.Builder` 자동구성에 반영되는지 확인해야 하며, 그것이 이 테스트의 목적이다 — 실패를 발견으로 보고 사용자에게 보고한다.

- [ ] **Step 5: 전체 테스트를 돌리고 커밋한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 286건 통과(283 + 3).

```bash
git add src/test/java/com/jhg/wms/resilience/
git commit -m "$(cat <<'EOF'
test(wms): OMS 다운·지연 시 재고가 오염되지 않음을 증명

"상대가 죽어도 재고는 오염되지 않는다"가 README의 핵심 주장인데
증거는 수동 관측 기록 한 번이 전부였다. read-timeout: 2s 설정도
동작 증거가 없었다.

죽은 OMS는 포트 1(아무도 듣지 않음)로, 느린 OMS는 JDK 내장 HttpServer의
3초 지연 응답으로 재현한다 — 새 의존성이 필요 없다.

타임아웃 단언은 상한만 건다. "정확히 2초"는 타이밍 의존이라 플레이키하다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 실사 겹침 경합을 재현한다

**Files:**
- Create: `src/test/java/com/jhg/wms/concurrency/CycleCountConcurrencyTest.java`

**Interfaces:**
- Consumes: `ConcurrencySupport.race`, `seedInventory`, `PID_BASE`
- Consumes: `CycleCountService.open(List<Long>, String): CycleCount` (기존)
- Produces: 없음 (다음 태스크가 이 테스트를 통과시킨다)

- [ ] **Step 1: 겹침 경합 테스트를 쓴다**

`src/test/java/com/jhg/wms/concurrency/CycleCountConcurrencyTest.java` 생성:

```java
package com.jhg.wms.concurrency;

import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.service.CycleCountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("실사 세션 개설의 동시성")
class CycleCountConcurrencyTest extends ConcurrencySupport {

    @Autowired CycleCountService cycleCountService;
    @Autowired CycleCountRepository cycleCountRepository;

    /**
     * CycleCountService.open()은 findOpenProductIds() 겹침 검사와 세션 생성 사이에 락이 없다
     * (코드의 ponytail 주석에 적힌 알려진 공백). 같은 상품이 두 세션에 함께 담기면
     * 나중 세션의 실물 수량은 이미 낡은 값이 되어, 센 시점과 적용 시점이 어긋난 조정이 남는다.
     */
    @Test
    void 같은_상품으로_두_세션을_동시에_열면_하나만_성공한다() {
        long pid = PID_BASE + 100;
        seedInventory(pid, 50);

        RaceResult result = race(2, i -> {
            cycleCountService.open(List.of(pid), "동시 개설 " + i);
            return true;
        });

        assertThat(result.succeeded())
                .as("겹침 검사와 생성 사이에 락이 없어 두 세션이 같이 열렸다")
                .isEqualTo(1);
        assertThat(cycleCountRepository.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*CycleCountConcurrencyTest*'
```

Expected: **FAIL** — `succeeded()`가 2로 나온다. 문서와 주석에만 있던 한계가 실행 가능한 재현으로 바뀐 것이다.

경합이 매번 잡히지 않고 간헐적으로 통과한다면, 두 스레드가 실제로 겹치도록 `race()` 호출 전에 세션 개설 경로가 충분히 무거운지 확인한다. 그래도 재현이 불안정하면 그 사실을 사용자에게 보고한다 — Task 7의 수정은 재현 여부와 무관하게 옳지만, "고쳐졌다"의 증거가 약해지기 때문이다.

- [ ] **Step 3: 재현 결과를 보고서에 기록한다**

실패하는 테스트를 커밋하지 않는다. **Task 6과 Task 7은 한 번의 작업으로 이어서 수행한다** —
재현이 곧 수정의 근거이고, 재현 없이 고치면 "고쳐졌다"의 증거가 없다.

보고서에 아래를 그대로 남긴다:

- 실패 시 `succeeded()`의 실제 값
- 실패 메시지 전문
- 5회 중 몇 회 실패했는지(간헐적 재현이면 그 사실 자체가 기록 대상)

기록했으면 곧바로 Task 7로 넘어간다.

---

### Task 7: 겹침 경합을 비관적 락으로 고친다

> Task 6과 이어서 같은 작업으로 수행한다. 재현(Task 6)과 수정(Task 7)이 한 커밋으로 묶인다 —
> 실패하는 테스트를 커밋에 남기지 않기 위해서다.

**Files:**
- Modify: `src/main/java/com/jhg/wms/repository/InventoryRepository.java`
- Modify: `src/main/java/com/jhg/wms/service/CycleCountService.java:34-60`

**Interfaces:**
- Consumes: Task 6의 `CycleCountConcurrencyTest`
- Produces: `InventoryRepository.findByProductIdWithLock(Long productId): Optional<Inventory>`

- [ ] **Step 1: 잠금 조회를 저장소에 추가한다**

`src/main/java/com/jhg/wms/repository/InventoryRepository.java`를 아래로 교체한다:

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(Long productId);
    List<Inventory> findByProductIdIn(Collection<Long> productIds);

    // 실사 세션 개설의 겹침 검사를 직렬화한다 — ReservationRepository.findByOrderIdWithLock과 같은 패턴.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.productId = :productId")
    Optional<Inventory> findByProductIdWithLock(Long productId);
}
```

- [ ] **Step 2: open()이 잠그고 검사하게 바꾼다**

`CycleCountService.open()`의 앞부분을 교체한다.

기존:

```java
    // ponytail: 겹침 검사(findOpenProductIds)와 세션 생성 사이에 락이 없다 — 동시에 두 요청이 들어오면
    // 같은 상품이 두 세션에 함께 담길 수 있다. 세션 개설은 사람이 수동으로 트리거하는 저빈도 작업이고
    // 재고 반영은 승인(approve) 단계에서 그 시점 장부로 재계산되므로, 지금은 데이터가 깨지지 않는다.
    // 실제로 충돌이 발생하면 cycle_count_item(product_id) 부분 유니크 인덱스나 락 기반 단일 쿼리로 올릴 것.
    @Transactional
    public CycleCount open(List<Long> productIds, String memo) {
        if (productIds == null || productIds.isEmpty())
            throw new IllegalArgumentException("실사 대상을 1개 이상 선택해야 합니다.");

        List<Long> distinct = productIds.stream().distinct().toList();
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(distinct).stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i));
        for (Long pid : distinct) {
            if (!inventories.containsKey(pid))
                throw new IllegalArgumentException("재고에 없는 상품입니다. productId=" + pid);
        }
```

교체 후:

```java
    /**
     * 실사 세션을 연다. 대상 상품의 재고 행을 <b>먼저 잠근 뒤</b> 겹침을 검사한다 —
     * 검사와 생성 사이에 락이 없으면 같은 상품이 두 세션에 함께 담기고,
     * 나중 세션의 실물 수량은 이미 낡은 값이 되어 센 시점과 적용 시점이 어긋난 조정이 남는다.
     *
     * <p>productId 오름차순으로 하나씩 잠근다. 겹치는 상품 집합을 두 요청이 서로 다른 순서로
     * 잠그면 데드락이 생기므로 순서를 고정해야 한다.
     * ponytail: 대상 수만큼 쿼리가 나가지만 세션 개설은 사람이 수동으로 트리거하는 저빈도 작업이라
     * 무해하다. 대상이 수백 개로 커지면 정렬된 IN + FOR UPDATE 단일 쿼리로 올릴 것.
     *
     * <p>부분 유니크 인덱스는 쓸 수 없다 — 조건이 cycle_count.status에 있는데 인덱스는
     * cycle_count_item에 걸어야 하고, 부분 인덱스의 WHERE는 다른 테이블 컬럼을 참조하지 못한다.
     */
    @Transactional
    public CycleCount open(List<Long> productIds, String memo) {
        if (productIds == null || productIds.isEmpty())
            throw new IllegalArgumentException("실사 대상을 1개 이상 선택해야 합니다.");

        List<Long> distinct = productIds.stream().distinct().sorted().toList();
        Map<Long, Inventory> inventories = new LinkedHashMap<>();
        for (Long pid : distinct) {
            Inventory inv = inventoryRepository.findByProductIdWithLock(pid)
                    .orElseThrow(() -> new IllegalArgumentException("재고에 없는 상품입니다. productId=" + pid));
            inventories.put(pid, inv);
        }
```

`Collectors`가 이 메서드에서 더는 쓰이지 않더라도 `approve()`가 계속 쓰므로 임포트는 그대로 둔다.
`LinkedHashMap`은 이미 임포트되어 있다.

- [ ] **Step 3: 재현 테스트가 통과하는지 확인한다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*CycleCountConcurrencyTest*'
```

Expected: PASS — `succeeded() == 1`.

- [ ] **Step 4: 5회 연속 돌려 안정성을 확인한다**

```bash
for i in 1 2 3 4 5; do
  JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
    ./gradlew test --tests '*CycleCountConcurrencyTest*' --rerun-tasks -q || echo "FAILED on run $i"
done
```

Expected: 5회 모두 통과. 데드락이 발생하면 여기서 드러난다.

- [ ] **Step 5: 전체 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 287건 통과(286 + 1). 기존 `CycleCountServiceTest`의 겹침 거부 테스트도 계속 통과해야 한다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/repository/InventoryRepository.java \
        src/main/java/com/jhg/wms/service/CycleCountService.java \
        src/test/java/com/jhg/wms/concurrency/CycleCountConcurrencyTest.java
git commit -m "$(cat <<'EOF'
fix(wms): 실사 세션 개설의 겹침 경합을 비관적 락으로 직렬화

주석에 알려진 공백으로 적혀 있던 것을 동시 요청으로 재현한 뒤 고쳤다.
겹침 검사와 생성 사이에 락이 없어 같은 상품이 두 세션에 함께 담겼고,
그러면 나중 세션의 실물 수량이 낡은 값이 되어 센 시점과 적용 시점이
어긋난 조정이 남는다.

대상 재고 행을 productId 오름차순으로 잠근 뒤 검사한다. 순서를 고정하지 않으면
겹치는 상품 집합을 두 요청이 반대로 잡아 데드락이 생긴다.

주석에 적혀 있던 부분 유니크 인덱스 방안은 성립하지 않아 폐기했다 —
조건이 cycle_count.status에 있는데 인덱스는 cycle_count_item에 걸어야 하고,
부분 인덱스의 WHERE는 다른 테이블 컬럼을 참조할 수 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 실사 승인·조정 동시 시나리오

**Files:**
- Modify: `src/test/java/com/jhg/wms/concurrency/CycleCountConcurrencyTest.java`

**Interfaces:**
- Consumes: `CycleCountService.saveCounts`, `submit`, `approve`, `assertAdjustable` (기존)
- Consumes: `ConcurrencySupport.race`, `seedInventory`, `onHandOf`

- [ ] **Step 1: 동시 승인 테스트를 추가한다**

`CycleCountConcurrencyTest`에 추가한다. 상단에 임포트를 더한다:

```java
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

테스트를 추가한다:

```java
    @Test
    void 같은_세션을_두_번_동시에_승인해도_한_번만_반영된다() {
        long pid = PID_BASE + 101;
        seedInventory(pid, 50);

        // 계수 45로 제출 — 승인되면 차이 -5가 COUNT 원장에 남아야 한다.
        CycleCount session = tx.execute(s -> cycleCountService.open(List.of(pid), "동시 승인"));
        long sessionId = session.getId();
        long itemId = tx.execute(s ->
                cycleCountService.findById(sessionId).getItems().get(0).getId());
        tx.executeWithoutResult(s -> cycleCountService.saveCounts(sessionId, Map.of(itemId, 45)));
        tx.executeWithoutResult(s -> cycleCountService.submit(sessionId));

        race(2, i -> {
            cycleCountService.approve(sessionId);
            return true;
        });

        assertThat(onHandOf(pid)).isEqualTo(45);          // 50 - 5, 한 번만 반영
        assertThat(tx.execute(s -> cycleCountService.findById(sessionId).getStatus()))
                .isEqualTo(CycleCountStatus.APPROVED);
        assertThat(tx.execute(s -> transactionRepository.findByReference("COUNT#" + sessionId).size()))
                .isEqualTo(1);
    }
```

`open()`과 `submit()`은 `actorProvider.current()`를 쓴다. `@SpringBootTest`에서 인증이 없으면 `SecurityContextActorProvider`가 `"system"`을 반환하므로 제출자와 승인자가 같아져 `approve()`가 자기승인 거부에 걸린다. 그래서 다음 스텝에서 행위자를 갈아끼운다.

- [ ] **Step 2: 행위자를 테스트에서 바꿀 수 있게 한다**

`CycleCountConcurrencyTest` 상단에 추가한다:

```java
import com.jhg.wms.config.ActorProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicReference;
```

클래스 선언에 `@Import`를 붙이고 내부 설정을 더한다:

```java
@DisplayName("실사 세션 개설의 동시성")
@Import(CycleCountConcurrencyTest.ActorConfig.class)
class CycleCountConcurrencyTest extends ConcurrencySupport {

    /** 제출자와 승인자가 달라야 approve()가 통과한다 — 테스트가 호출 시점마다 갈아끼운다. */
    static final AtomicReference<String> ACTOR = new AtomicReference<>("operator");

    @TestConfiguration
    static class ActorConfig {
        // @Primary가 없으면 SecurityContextActorProvider(@Component)와 둘이 되어
        // NoUniqueBeanDefinitionException으로 컨텍스트가 뜨지 않는다.
        @Bean
        @Primary
        ActorProvider testActorProvider() {
            return ACTOR::get;
        }
    }
```

`동시_승인` 테스트의 `submit` 다음, `race` 앞에 한 줄을 넣는다:

```java
        ACTOR.set("manager");   // 제출자(operator)와 다른 승인자
```

그리고 각 테스트 시작 시 기본값으로 되돌린다:

```java
    @org.junit.jupiter.api.BeforeEach
    void 행위자를_초기화한다() {
        ACTOR.set("operator");
    }
```

`ActorProvider`가 함수형 인터페이스(`String current()`)이므로 `ACTOR::get`이 바로 구현이 된다.

- [ ] **Step 3: 승인 중 조정이 거부되는지 확인하는 테스트를 추가한다**

```java
    @Test
    void 실사가_열려있는_동안_조정은_거부된다() {
        long pid = PID_BASE + 102;
        seedInventory(pid, 30);
        tx.executeWithoutResult(s -> cycleCountService.open(List.of(pid), "조정 차단 확인"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> cycleCountService.assertAdjustable(pid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("실사가 진행 중인 상품");

        assertThat(onHandOf(pid)).isEqualTo(30);   // 재고 불변
    }
```

- [ ] **Step 4: 테스트를 돌린다**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests '*CycleCountConcurrencyTest*'
```

Expected: 3건 통과.

동시 승인에서 두 스레드가 모두 성공하고 `COUNT` 원장이 2행이 되면, 그것이 발견이다 —
`approve()`의 상태 검사와 반영 사이에도 같은 종류의 공백이 있다는 뜻이므로 사용자에게 보고한다.

- [ ] **Step 5: 전체 테스트 후 커밋**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 289건 통과(287 + 2).

```bash
git add src/test/java/com/jhg/wms/concurrency/CycleCountConcurrencyTest.java
git commit -m "$(cat <<'EOF'
test(wms): 실사 승인·조정의 동시 시나리오 증명

같은 세션을 두 번 동시에 승인해도 COUNT 원장이 한 행만 남고 재고가 한 번만
반영되는지, 실사가 열린 동안 조정이 거부되는지를 실제 동시 요청으로 확인한다.

제출자와 승인자가 달라야 approve()가 통과하므로 ActorProvider를 테스트에서
갈아끼울 수 있게 @TestConfiguration으로 주입한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: 문서 현행화

**Files:**
- Modify: `README.md`
- Modify: `docs/wms-business-roadmap.md`
- Modify: `docs/oms-wms-manual-verification.md`

**Interfaces:**
- Consumes: Task 1~8의 결과(최종 테스트 수, 측정된 CI 시간)

- [ ] **Step 1: README의 테스트 수와 주장·증거 연결을 고친다**

`README.md`에서 테스트 수를 최종 값으로 바꾼다.

```markdown
| 테스트 | 289개 (도메인 · 서비스 · MockMvc 슬라이스 · 실서블릿 보안 통합 · **실제 동시 요청 경합**) |
```

`## 회복탄력성` 섹션의 표 아래에 문단을 추가한다:

```markdown
이 표의 항목들은 주장이 아니라 **실행 가능한 증거**로 고정돼 있습니다. 오버셀 방지는 실제 스레드
경합으로(`InventoryConcurrencyTest`), OMS 다운·지연은 죽은 포트와 JDK 내장 HTTP 서버로
(`OmsDownTest`·`OmsSlowTest`), 실사 세션 겹침은 동시 개설로(`CycleCountConcurrencyTest`)
매 PR마다 CI에서 검증됩니다. 원장 불변식(Σdelta == onHand)은 모든 동시성 시나리오의
`@AfterEach` 후크로 자동 확인되고, 수불대장 화면에도 대조 결과가 표시됩니다.
```

- [ ] **Step 2: 로드맵을 현행화한다**

`docs/wms-business-roadmap.md`에서:

```markdown
최종 현행화: 2026-08-26
```

테스트 수 줄을 바꾼다:

```markdown
- [x] WMS 전체 테스트 재실행 (289개 통과, 2026-08-26)
```

`## 현재 완료` 표에 행을 추가한다:

```markdown
| 정합성 증명 | 실제 동시 요청 기반 오버셀·불변식·회복탄력성·실사 경합 검증 (V3.0) |
```

`## 1차 이후 선택 로드맵`의 4번 항목 본문 끝에 한 줄을 더한다:

```markdown
V3.0에서 실사의 계수-승인 사이 물리 이동 문제는 위치 단위 동결이 필요함이 확인됐다. 이 항목이 그 전제다.
```

- [ ] **Step 3: 수동 검증 문서에 로컬 실행 전제를 추가한다**

`docs/oms-wms-manual-verification.md`의 `## 개발 DB 초기화 절차 (OMS·WMS 동시)` 섹션에서
초기화 절차를 Postgres 기준으로 바꾼다.

기존 2·3번 항목:

```markdown
2. OMS를 `--spring.profiles.active=local`로 기동해 스키마를 재생성한다(`ddl-auto: create`).
3. WMS를 `--spring.profiles.active=local`로 기동해 스키마를 재생성한다.
```

교체 후:

```markdown
2. OMS를 `--spring.profiles.active=local`로 기동해 스키마를 재생성한다(`ddl-auto: create`).
3. WMS도 `--spring.profiles.active=local`로 기동해 스키마를 재생성한다.
   WMS는 V3.0부터 PostgreSQL 17을 쓴다 — 먼저 `brew services start postgresql@17`이 떠 있어야 한다.
   개발용은 `wms`, 테스트용은 `wms_test` 데이터베이스다.
```

- [ ] **Step 4: 전체 테스트 후 커밋**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
git add README.md docs/wms-business-roadmap.md docs/oms-wms-manual-verification.md
git commit -m "$(cat <<'EOF'
docs(wms): V3.0 정합성 증명 반영

README의 회복탄력성 표가 주장만 나열하고 있었다. 각 항목이 어떤 테스트로
CI에서 검증되는지 연결해, 주장과 증거가 문서에서 이어지게 했다.

로컬 실행 전제(brew services start postgresql@17)와 dev/test DB 분리도 명시한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## 최종 확인

- [ ] `./gradlew test` — 289건 통과, 실패 0
- [ ] `dropdb wms_test && createdb -O wms wms_test` 후 재실행 — 깨끗한 DB에서도 통과
- [ ] 동시성 테스트 5회 연속 통과 (플레이키 없음)
- [ ] `grep -rn "h2" build.gradle src/main src/test --include='*.yml' --include='*.gradle'` — 결과 없음
- [ ] PR 생성 후 CI 초록 확인. **CI 시간이 3분을 넘으면 병합 전에 보고한다.**
