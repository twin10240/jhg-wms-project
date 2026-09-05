# 예약 체류 분석 도구 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예약이 재고를 붙들고 있던 시간을 재는 읽기 전용 MCP 도구 두 개와 그 숫자를 읽는 규율 스킬을 만든다.

**Architecture:** 기존 분석 3종(반품·실사·원장)의 계층을 그대로 복제한다 — JPQL로 엔티티를 끌어와 자바에서 집계하는 서비스, 계산하지 않고 위임만 하는 얇은 REST 컨트롤러, 경로를 하드코딩하는 python 클라이언트, 읽는 규율을 docstring에 담은 MCP 도구. 새로 발명하는 층은 없다.

**Tech Stack:** Spring Boot 3.5 / JPA(JPQL + `@EntityGraph`) / JUnit5 + AssertJ + `@DataJpaTest` + MockMvc / python MCPServer + httpx + pytest

## Global Constraints

- 설계 스펙은 `docs/superpowers/specs/v9/2026-09-04-reservation-dwell-design.md`다. 계약 항목은 거기서 정한 그대로 구현한다.
- **체류 = `createdAt` → 종료 시각.** SHIPPED의 종료는 `issuedAt`, RELEASED는 `releasedAt`. 출고 시각 전용 컬럼은 없다.
- **`deliveredAt`은 쓰지 않는다.**
- **출고 경로와 해제 경로를 한 분포로 합치지 않는다.**
- 구간 판정은 **종료 시각**이고 응답에 `basis: "endedAt"`을 싣는다.
- 경로별 `count`가 0이면 `medianMinutes`·`p90Minutes`·`maxMinutes`는 **null**이다. 0으로 내지 않는다.
- 반개구간: `>= fromAt`, `< toAtExclusive`. `to`는 그날 하루를 포함한다(`to.plusDays(1)`).
- `Instant` ↔ `LocalDate` 변환은 `ZoneId.systemDefault()`. 설정값으로 빼지 않는다.
- 새 파일의 클래스 주석은 기존 분석 계층과 같은 밀도로 **왜 그렇게 정했는지**를 적는다. 무엇을 하는지만 적지 않는다.
- 커밋 메시지는 한국어 현재형 서술체(기존 이력과 동일). 각 태스크 끝에서 커밋한다.

---

### Task 1: 체류 집계 — 조회 두 개와 분포 계산

**Files:**
- Modify: `src/main/java/com/jhg/wms/repository/ReservationRepository.java`
- Create: `src/main/java/com/jhg/wms/service/ReservationAnalyticsService.java`
- Test: `src/test/java/com/jhg/wms/service/ReservationAnalyticsServiceTest.java`

**Interfaces:**
- Consumes: `Reservation.reserve(UUID, Long, Map<Long,Integer>)`, `ship()`, `issueShipment(Instant)`, `release()`, `getCreatedAt()`, `getIssuedAt()`, `getReleasedAt()`, `getStatus()`, `getQtyByProductId()` / `InventoryRepository.findByProductIdIn(Collection<Long>)`
- Produces: `ReservationAnalyticsService.DwellStats(int count, Long medianMinutes, Long p90Minutes, Long maxMinutes)`, `ReservationAnalyticsService.DwellReport(LocalDate from, LocalDate to, String basis, DwellStats shipped, DwellStats released, int stillOpen, int excludedMissingCreatedAt)`, `dwell(LocalDate, LocalDate)`, `ReservationRepository.findEndedBetween(Instant, Instant)`, `ReservationRepository.countOpenAt(Instant)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/service/ReservationAnalyticsServiceTest.java` 신규:

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 체류 분석.
 *
 * <p>가장 중요한 판단은 <b>출고 체류와 해제 체류를 섞지 않는 것</b>이다. 정상 처리에 걸린
 * 시간과 헛되이 묶여 있던 시간은 조치할 곳이 다르다 — 한 분포로 합치면 중앙값이 어느 쪽
 * 이야기인지 말할 수 없게 된다.
 *
 * <p>그다음이 <b>안 끝난 것을 밝히는 것</b>이다. 오래 붙들려 있는 예약일수록 아직 안 끝나
 * 분포에 안 잡히므로, stillOpen 없이 중앙값만 읽으면 실제보다 짧게 보인다.
 */
@DataJpaTest
class ReservationAnalyticsServiceTest {

    @Autowired ReservationRepository reservationRepository;
    @Autowired InventoryRepository inventoryRepository;
    ReservationAnalyticsService service;

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private long orderSeq = 7000L;

    @BeforeEach
    void setUp() {
        service = new ReservationAnalyticsService(reservationRepository, inventoryRepository);
    }

    /** 테스트 시각을 서비스와 같은 존으로 만든다 — 존이 어긋나면 구간 경계가 하루씩 밀린다. */
    private static Instant at(LocalDate day, int hour) {
        return LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant();
    }

    private void seedProduct(long productId, String name) {
        inventoryRepository.saveAndFlush(Inventory.create(productId, name, 100));
    }

    /** 시각은 도메인이 now()로 박으므로 저장 후 리플렉션으로 덮어쓴다(실사 테스트와 같은 수법). */
    private Reservation seedShipped(Instant createdAt, Instant issuedAt, Map<Long, Integer> qty) {
        Reservation r = Reservation.reserve(UUID.randomUUID(), orderSeq++, qty);
        r.ship();
        r.issueShipment(Instant.now());
        reservationRepository.saveAndFlush(r);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        ReflectionTestUtils.setField(r, "issuedAt", issuedAt);
        return reservationRepository.saveAndFlush(r);
    }

    private Reservation seedReleased(Instant createdAt, Instant releasedAt, Map<Long, Integer> qty) {
        Reservation r = Reservation.reserve(UUID.randomUUID(), orderSeq++, qty);
        r.release();
        reservationRepository.saveAndFlush(r);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        ReflectionTestUtils.setField(r, "releasedAt", releasedAt);
        return reservationRepository.saveAndFlush(r);
    }

    private Reservation seedOpen(Instant createdAt, Map<Long, Integer> qty) {
        Reservation r = Reservation.reserve(UUID.randomUUID(), orderSeq++, qty);
        reservationRepository.saveAndFlush(r);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return reservationRepository.saveAndFlush(r);
    }

    @Test
    void 출고_체류와_해제_체류를_따로_낸다() {
        seedProduct(1L, "볼펜");
        // 출고: 2시간, 4시간
        seedShipped(at(LocalDate.of(2026, 9, 5), 10), at(LocalDate.of(2026, 9, 5), 12), Map.of(1L, 1));
        seedShipped(at(LocalDate.of(2026, 9, 6), 10), at(LocalDate.of(2026, 9, 6), 14), Map.of(1L, 1));
        // 해제: 10시간
        seedReleased(at(LocalDate.of(2026, 9, 7), 0), at(LocalDate.of(2026, 9, 7), 10), Map.of(1L, 1));

        var report = service.dwell(FROM, TO);

        assertThat(report.basis()).isEqualTo("endedAt");
        assertThat(report.shipped().count()).isEqualTo(2);
        assertThat(report.shipped().maxMinutes()).isEqualTo(240L);
        assertThat(report.released().count()).isEqualTo(1);
        assertThat(report.released().maxMinutes()).isEqualTo(600L);
    }

    @Test
    void 잴_것이_없으면_null이다() {
        var report = service.dwell(FROM, TO);

        assertThat(report.shipped().count()).isZero();
        assertThat(report.shipped().medianMinutes()).isNull();
        assertThat(report.shipped().p90Minutes()).isNull();
        assertThat(report.shipped().maxMinutes()).isNull();
    }

    @Test
    void createdAt이_없는_예약은_세지_않고_제외로_밝힌다() {
        seedProduct(1L, "볼펜");
        Reservation r = seedShipped(at(LocalDate.of(2026, 9, 5), 10), at(LocalDate.of(2026, 9, 5), 12), Map.of(1L, 1));
        ReflectionTestUtils.setField(r, "createdAt", null);
        reservationRepository.saveAndFlush(r);

        var report = service.dwell(FROM, TO);

        assertThat(report.shipped().count()).isZero();
        assertThat(report.excludedMissingCreatedAt()).isEqualTo(1);
    }

    @Test
    void 구간_끝에_아직_안_끝난_예약을_stillOpen으로_센다() {
        seedProduct(1L, "볼펜");
        seedOpen(at(LocalDate.of(2026, 9, 10), 9), Map.of(1L, 1));
        // 구간이 끝난 뒤에 출고된 예약도 그 시점엔 열려 있었다
        seedShipped(at(LocalDate.of(2026, 9, 29), 9), at(LocalDate.of(2026, 10, 5), 9), Map.of(1L, 1));

        var report = service.dwell(FROM, TO);

        assertThat(report.stillOpen()).isEqualTo(2);
        assertThat(report.shipped().count()).isZero();   // 종료가 구간 밖이라 분포엔 안 들어간다
    }

    @Test
    void 종료가_구간_밖이면_분포에서_뺀다() {
        seedProduct(1L, "볼펜");
        seedShipped(at(LocalDate.of(2026, 8, 20), 10), at(LocalDate.of(2026, 8, 20), 12), Map.of(1L, 1));

        assertThat(service.dwell(FROM, TO).shipped().count()).isZero();
    }

    @Test
    void 역전된_구간은_거절한다() {
        assertThatThrownBy(() -> service.dwell(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.service.ReservationAnalyticsServiceTest'`
Expected: 컴파일 실패 — `ReservationAnalyticsService` 심볼을 찾을 수 없음.

- [ ] **Step 3: 조회 두 개를 추가한다**

`ReservationRepository.java`에 import를 더한다:

```java
import java.time.Instant;
```

그리고 `findAllByOrderByIdDesc()` 아래에 추가:

```java
    /**
     * 종료 시각이 구간에 든 예약. 체류 분석이 쓰는 유일한 조회다.
     *
     * <p><b>생성 시각이 아니라 종료 시각 기준이다.</b> 생성 시각으로 자르면 구간 끝무렵에 생긴
     * 예약이 아직 안 끝나 빠지고, 나중에 같은 기간을 다시 부르면 그때는 끝나 있어서 숫자가 바뀐다.
     * 종료 시각으로 자르면 그 구간은 한 번 확정되면 변하지 않는다 — 보고서에 인용할 수 있는 쪽이다.
     *
     * <p>SHIPPED인데 issuedAt이 null인 행은 비교가 실패해 여기 안 들어온다. 별도 제외 카운터를
     * 두지 않은 이유: shipAll이 ship() 직후 항상 issueShipment를 부르고 송장 없는 기존 주문까지
     * 메우므로 앞으로 생기지 않는다.
     *
     * <p>qtyByProductId는 지연로딩이라 상품별 집계에서 터진다 — 여기서 즉시 페치한다.
     */
    @EntityGraph(attributePaths = "qtyByProductId")
    @Query("""
           SELECT r FROM Reservation r
           WHERE (r.status = com.jhg.wms.domain.ReservationStatus.SHIPPED
                  AND r.issuedAt >= :fromAt AND r.issuedAt < :toAtExclusive)
              OR (r.status = com.jhg.wms.domain.ReservationStatus.RELEASED
                  AND r.releasedAt >= :fromAt AND r.releasedAt < :toAtExclusive)
           ORDER BY r.id
           """)
    List<Reservation> findEndedBetween(Instant fromAt, Instant toAtExclusive);

    /**
     * 그 시각에 아직 끝나지 않았던 예약 수. 체류 분포의 <b>생존 편향 크기</b>다.
     *
     * <p>오래 붙들려 있는 예약일수록 아직 안 끝나 분포에 안 잡힌다. 이 수를 모르고 중앙값만
     * 인용하면 실제보다 짧게 보인다.
     *
     * <p>createdAt이 null인 행은 세지 않는다 — 언제 시작했는지 모르면 그 시각에 열려 있었는지도 모른다.
     */
    @Query("""
           SELECT COUNT(r) FROM Reservation r
           WHERE r.createdAt IS NOT NULL AND r.createdAt < :at
             AND (r.status = com.jhg.wms.domain.ReservationStatus.RESERVED
                  OR (r.status = com.jhg.wms.domain.ReservationStatus.SHIPPED AND r.issuedAt >= :at)
                  OR (r.status = com.jhg.wms.domain.ReservationStatus.RELEASED AND r.releasedAt >= :at))
           """)
    long countOpenAt(Instant at);
```

- [ ] **Step 4: 서비스를 만든다**

`src/main/java/com/jhg/wms/service/ReservationAnalyticsService.java` 신규:

```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Reservation;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 예약 체류 분석 조회. 읽기만 한다 — 예약·재고를 만들지도 고치지도 않는다.
 *
 * <p>LLM을 부르지 않는다. 숫자를 읽고 무엇을 쓸지 정하는 일은 MCP 클라이언트의 모델이 한다
 * ({@code CycleCountAnalyticsService}와 같은 규칙이다).
 *
 * <p><b>체류는 {@code createdAt}부터 종료 시각까지다.</b> SHIPPED의 종료는 {@code issuedAt}이다 —
 * 출고 시각 전용 컬럼은 없고, {@code Reservation.releasedAt} 주석이 그 부재를 의도로 적어뒀다
 * ("같은 사실을 두 컬럼에 적으면 언젠가 둘이 어긋난다"). {@code deliveredAt}은 쓰지 않는다:
 * 재고는 출고 시점에 이미 나갔고 그 뒤 며칠이 걸리든 창고에 붙들려 있지 않다.
 *
 * <p><b>출고 경로와 해제 경로를 합치지 않는다.</b> 출고 체류는 정상 처리에 걸린 시간이고
 * 해제 체류는 헛되이 묶여 있던 시간이다. 조치할 곳이 서로 다른데 숫자 하나로 뭉개면
 * 그 숫자로는 아무것도 못 고친다.
 *
 * <p><b>구간 판정은 종료 시각이다</b>({@code basis="endedAt"}). 실사와 기준이 다른 이유는
 * {@code ReservationRepository.findEndedBetween} 주석에 있다.
 */
@Service
@Transactional(readOnly = true)
public class ReservationAnalyticsService {

    /**
     * 예약 시각은 Instant인데(서비스 경계를 넘는 값이라 의도적이다) 조회 인자는 LocalDate라
     * 존이 필요하다. 단일 창고라 설정값으로 빼지 않는다 — 필요해지면 그때 뺀다.
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;

    public ReservationAnalyticsService(ReservationRepository reservationRepository,
                                       InventoryRepository inventoryRepository) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * @param medianMinutes count가 0이면 <b>null</b>이다. 0으로 내면 "0분 만에 끝났다"로 읽힌다 —
     *                      잴 것이 없는 것과 다르다({@code accuracy: null}과 같은 규칙).
     */
    public record DwellStats(int count, Long medianMinutes, Long p90Minutes, Long maxMinutes) {}

    /**
     * @param stillOpen to 시점에 아직 끝나지 않은 예약 수. 생존 편향의 크기다.
     * @param excludedMissingCreatedAt createdAt이 null이라 잴 수 없었던 예약 수. 조용히 빼면
     *                                 분모를 속이는 것과 같다.
     */
    public record DwellReport(LocalDate from, LocalDate to, String basis,
                              DwellStats shipped, DwellStats released,
                              int stillOpen, int excludedMissingCreatedAt) {}

    /** 기간 내 끝난 예약의 체류 분포. 출고와 해제를 각각 낸다. */
    public DwellReport dwell(LocalDate from, LocalDate to) {
        List<Long> shipped = new ArrayList<>();
        List<Long> released = new ArrayList<>();
        int excluded = 0;

        for (Reservation r : endedIn(from, to)) {
            if (r.getCreatedAt() == null) { excluded++; continue; }
            long minutes = Duration.between(r.getCreatedAt(), endedAt(r)).toMinutes();
            switch (r.getStatus()) {
                case SHIPPED -> shipped.add(minutes);
                case RELEASED -> released.add(minutes);
                case RESERVED -> { }   // 조회가 걸러내므로 여기 오지 않는다
            }
        }

        long openAtEnd = reservationRepository.countOpenAt(startOf(to.plusDays(1)));
        return new DwellReport(from, to, "endedAt",
                stats(shipped), stats(released), (int) openAtEnd, excluded);
    }

    private static DwellStats stats(List<Long> minutes) {
        if (minutes.isEmpty()) return new DwellStats(0, null, null, null);
        List<Long> sorted = minutes.stream().sorted().toList();
        return new DwellStats(sorted.size(), percentile(sorted, 0.5), percentile(sorted, 0.9),
                sorted.get(sorted.size() - 1));
    }

    /**
     * 정렬된 목록의 p분위(nearest-rank). <b>보간하지 않는다</b> — 표본이 작을 때 실제로 없던
     * 체류시간을 만들어내고, 보고서는 그 값을 실측치로 인용하게 된다.
     */
    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** SHIPPED는 issuedAt, RELEASED는 releasedAt. 조회가 그 둘만 돌려준다. */
    private static Instant endedAt(Reservation r) {
        return switch (r.getStatus()) {
            case SHIPPED -> r.getIssuedAt();
            case RELEASED -> r.getReleasedAt();
            case RESERVED -> null;
        };
    }

    /** to는 그날 하루를 포함한다 — 화면·보고서가 "9/1~9/3"을 3일로 읽는 것과 맞춘다. */
    List<Reservation> endedIn(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        return reservationRepository.findEndedBetween(startOf(from), startOf(to.plusDays(1)));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.service.ReservationAnalyticsServiceTest'`
Expected: 6개 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/repository/ReservationRepository.java \
        src/main/java/com/jhg/wms/service/ReservationAnalyticsService.java \
        src/test/java/com/jhg/wms/service/ReservationAnalyticsServiceTest.java
git commit -m "feat(wms): 예약 체류 분포를 출고·해제 경로로 나눠 낸다"
```

---

### Task 2: 상품별 체류 묶음

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/ReservationAnalyticsService.java`
- Test: `src/test/java/com/jhg/wms/service/ReservationAnalyticsServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `endedIn(LocalDate, LocalDate)`, `endedAt(Reservation)`, `percentile(List<Long>, double)`
- Produces: `ReservationAnalyticsService.ProductDwell(Long productId, String productName, int occurrences, long medianMinutes, long maxMinutes, int shippedCount, int releasedCount)`, `dwellByProduct(LocalDate, LocalDate)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ReservationAnalyticsServiceTest.java`에 추가:

```java
    @Test
    void 상품별로_묶고_반복_횟수가_많은_상품이_먼저_온다() {
        seedProduct(1L, "볼펜");
        seedProduct(2L, "노트");
        // 1번 상품: 2건 (2시간, 4시간) / 2번 상품: 1건 (10시간)
        seedShipped(at(LocalDate.of(2026, 9, 5), 10), at(LocalDate.of(2026, 9, 5), 12), Map.of(1L, 1));
        seedShipped(at(LocalDate.of(2026, 9, 6), 10), at(LocalDate.of(2026, 9, 6), 14), Map.of(1L, 1));
        seedReleased(at(LocalDate.of(2026, 9, 7), 0), at(LocalDate.of(2026, 9, 7), 10), Map.of(2L, 1));

        var rows = service.dwellByProduct(FROM, TO);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).productId()).isEqualTo(1L);
        assertThat(rows.get(0).productName()).isEqualTo("볼펜");
        assertThat(rows.get(0).occurrences()).isEqualTo(2);
        assertThat(rows.get(0).shippedCount()).isEqualTo(2);
        assertThat(rows.get(0).releasedCount()).isZero();
        assertThat(rows.get(0).maxMinutes()).isEqualTo(240L);
        assertThat(rows.get(1).productId()).isEqualTo(2L);
        assertThat(rows.get(1).releasedCount()).isEqualTo(1);
    }

    @Test
    void 예약_하나가_담은_상품마다_체류가_계상된다() {
        seedProduct(1L, "볼펜");
        seedProduct(2L, "노트");
        seedShipped(at(LocalDate.of(2026, 9, 5), 10), at(LocalDate.of(2026, 9, 5), 13),
                Map.of(1L, 1, 2L, 5));

        var rows = service.dwellByProduct(FROM, TO);

        // 예약은 하나지만 상품이 둘이라 행이 둘이다 — occurrences 합은 예약 건수가 아니다.
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.occurrences()).isEqualTo(1);
            assertThat(row.medianMinutes()).isEqualTo(180L);
        });
    }

    @Test
    void 차이가_없으면_빈_목록이다() {
        assertThat(service.dwellByProduct(FROM, TO)).isEmpty();
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.service.ReservationAnalyticsServiceTest'`
Expected: 컴파일 실패 — `dwellByProduct` 메서드 없음.

- [ ] **Step 3: 구현한다**

`ReservationAnalyticsService.java`의 import에 더한다:

```java
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.ReservationStatus;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
```

`DwellReport` record 아래에 record를 더한다:

```java
    /**
     * @param occurrences 그 상품이 든 예약 중 체류를 잰 건수. <b>합은 예약 건수가 아니다</b> —
     *                    예약 하나가 상품 여럿을 담으면 담은 수만큼 계상된다.
     */
    public record ProductDwell(Long productId, String productName,
                               int occurrences, long medianMinutes, long maxMinutes,
                               int shippedCount, int releasedCount) {}
```

`dwell(...)` 아래에 메서드를 더한다:

```java
    /**
     * 상품별 체류 묶음. 반복해서 오래 붙들린 상품이 먼저 온다.
     *
     * <p>정렬은 <b>반복 횟수가 먼저</b>고 그다음이 중앙값이다. 한 번 아주 오래 걸린 것보다
     * 여러 번 반복해서 오래 걸리는 쪽이 로케이션·재고 부족 같은 구조적 원인을 가리키기 때문이다
     * ({@code CycleCountAnalyticsService.variances}와 같은 규칙이다).
     */
    public List<ProductDwell> dwellByProduct(LocalDate from, LocalDate to) {
        Map<Long, List<Long>> minutesByProduct = new LinkedHashMap<>();
        Map<Long, int[]> countsByProduct = new HashMap<>();   // [shipped, released]

        for (Reservation r : endedIn(from, to)) {
            if (r.getCreatedAt() == null) continue;
            long minutes = Duration.between(r.getCreatedAt(), endedAt(r)).toMinutes();
            for (Long productId : r.getQtyByProductId().keySet()) {
                minutesByProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(minutes);
                int[] counts = countsByProduct.computeIfAbsent(productId, k -> new int[2]);
                if (r.getStatus() == ReservationStatus.SHIPPED) counts[0]++; else counts[1]++;
            }
        }
        if (minutesByProduct.isEmpty()) return List.of();

        // Collectors.toMap은 값이 null이면 NPE다. 이름은 나중에 도입된 컬럼이라 null일 수 있고,
        // 이름 없는 상품 하나가 보고서 전체를 막으면 안 된다(variances와 같은 이유).
        Map<Long, String> nameByProduct = new HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(minutesByProduct.keySet()))
            nameByProduct.put(inv.getProductId(), inv.getProductName());

        List<ProductDwell> result = new ArrayList<>();
        minutesByProduct.forEach((productId, minutes) -> {
            List<Long> sorted = minutes.stream().sorted().toList();
            int[] counts = countsByProduct.get(productId);
            result.add(new ProductDwell(productId, nameByProduct.get(productId),
                    sorted.size(), percentile(sorted, 0.5), sorted.get(sorted.size() - 1),
                    counts[0], counts[1]));
        });

        result.sort(Comparator.comparingInt(ProductDwell::occurrences).reversed()
                .thenComparing(Comparator.comparingLong(ProductDwell::medianMinutes).reversed())
                .thenComparing(ProductDwell::productId));
        return result;
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.service.ReservationAnalyticsServiceTest'`
Expected: 9개 테스트 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/ReservationAnalyticsService.java \
        src/test/java/com/jhg/wms/service/ReservationAnalyticsServiceTest.java
git commit -m "feat(wms): 체류를 상품별로 묶어 반복 횟수 순으로 낸다"
```

---

### Task 3: REST 컨트롤러와 400 평문 계약

**Files:**
- Create: `src/main/java/com/jhg/wms/web/ReservationAnalyticsController.java`
- Modify: `src/main/java/com/jhg/wms/web/AnalyticsErrorAdvice.java`
- Test: `src/test/java/com/jhg/wms/web/ReservationAnalyticsControllerTest.java`

**Interfaces:**
- Consumes: `ReservationAnalyticsService.dwell(LocalDate, LocalDate)`, `dwellByProduct(LocalDate, LocalDate)`
- Produces: `GET /api/analytics/reservation-dwell?from=&to=`, `GET /api/analytics/reservation-dwell-by-product?from=&to=`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/web/ReservationAnalyticsControllerTest.java` 신규:

```java
package com.jhg.wms.web;

import com.jhg.wms.service.ReservationAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 체류 조회 REST. <b>응답 필드 이름을 고정한다</b> — MCP 클라이언트가 이 이름을 그대로 읽고,
 * 스킬이 그 이름으로 보고서 규율을 적었다. 여기서 이름이 바뀌면 둘 다 조용히 어긋난다.
 */
// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic.
@WebMvcTest(ReservationAnalyticsController.class)
@Import(SecurityConfig.class)
class ReservationAnalyticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ReservationAnalyticsService service;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 체류_집계_응답_필드를_고정한다() throws Exception {
        given(service.dwell(any(), any())).willReturn(
                new ReservationAnalyticsService.DwellReport(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "endedAt",
                        new ReservationAnalyticsService.DwellStats(2, 120L, 240L, 240L),
                        new ReservationAnalyticsService.DwellStats(0, null, null, null),
                        3, 1));

        mockMvc.perform(get("/api/analytics/reservation-dwell")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("endedAt"))
                .andExpect(jsonPath("$.shipped.count").value(2))
                .andExpect(jsonPath("$.shipped.medianMinutes").value(120))
                .andExpect(jsonPath("$.released.medianMinutes").doesNotExist())
                .andExpect(jsonPath("$.stillOpen").value(3))
                .andExpect(jsonPath("$.excludedMissingCreatedAt").value(1));
    }

    @Test
    void 상품별_응답_필드를_고정한다() throws Exception {
        given(service.dwellByProduct(any(), any())).willReturn(List.of(
                new ReservationAnalyticsService.ProductDwell(11L, "볼펜", 2, 120L, 240L, 2, 0)));

        mockMvc.perform(get("/api/analytics/reservation-dwell-by-product")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(11))
                .andExpect(jsonPath("$[0].occurrences").value(2))
                .andExpect(jsonPath("$[0].shippedCount").value(2));
    }

    @Test
    void 역전된_구간은_400_평문이다() throws Exception {
        given(service.dwell(any(), any()))
                .willThrow(new IllegalArgumentException("시작일이 종료일보다 뒤입니다."));

        mockMvc.perform(get("/api/analytics/reservation-dwell")
                        .param("from", "2026-09-30").param("to", "2026-09-01")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("시작일이 종료일보다 뒤입니다."));
    }
}
```

`@Import(SecurityConfig.class)` + `httpBasic("wms", "wms")`가 필요한 이유: `/api/**`는 `apiChain`(Basic·CSRF 비활성·401 직접 응답)에 걸리므로 인증 없이 부르면 401이 나와 필드 검증까지 못 간다. `DbUserDetailsService`를 목으로 채우는 것도 같은 이유다(`SecurityConfig`가 그 빈을 요구한다). `CycleCountAnalyticsControllerTest`와 같은 구성이다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.web.ReservationAnalyticsControllerTest'`
Expected: 컴파일 실패 — `ReservationAnalyticsController` 없음.

- [ ] **Step 3: 컨트롤러를 만든다**

`src/main/java/com/jhg/wms/web/ReservationAnalyticsController.java` 신규:

```java
package com.jhg.wms.web;

import com.jhg.wms.service.ReservationAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 예약 체류 분석 조회 REST. MCP 서버가 이것을 부른다.
 *
 * <p>계산하지 않는다 — 서비스에 위임하고 레코드를 그대로 직렬화할 뿐이다.
 * 여기에 집계를 넣으면 화면과 보고서가 다른 숫자를 낼 수 있게 된다
 * ({@code CycleCountAnalyticsController}와 같은 규칙이다).
 *
 * <p>{@code from}·{@code to}에 기본값을 두지 않는다. 보고서는 분모가 무엇인지 분명해야 한다.
 *
 * <p><b>소비자</b>: {@code mcp-server/wms_mcp/client.py}가 이 경로와 파라미터 이름을 그대로
 * 하드코딩해 부른다. 여기서 바꾸면 그쪽도 같이 고쳐야 한다(Java 테스트는 그 불일치를 잡지 못한다).
 *
 * <p>400 평문 오류 계약은 {@link AnalyticsErrorAdvice}가 담당한다.
 */
@RestController
@RequestMapping("/api/analytics")
public class ReservationAnalyticsController {

    private final ReservationAnalyticsService reservationAnalyticsService;

    public ReservationAnalyticsController(ReservationAnalyticsService reservationAnalyticsService) {
        this.reservationAnalyticsService = reservationAnalyticsService;
    }

    @GetMapping("/reservation-dwell")
    public ReservationAnalyticsService.DwellReport dwell(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reservationAnalyticsService.dwell(from, to);
    }

    @GetMapping("/reservation-dwell-by-product")
    public List<ReservationAnalyticsService.ProductDwell> dwellByProduct(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reservationAnalyticsService.dwellByProduct(from, to);
    }
}
```

- [ ] **Step 4: 오류 핸들러에 등록한다**

`AnalyticsErrorAdvice.java`의 `assignableTypes`에 더한다. 등록하지 않으면 400 평문 계약이 이 컨트롤러에만 적용되지 않아 조용히 깨진다:

```java
@RestControllerAdvice(assignableTypes = {
        ReturnAnalyticsController.class,
        CycleCountAnalyticsController.class,
        InventoryLedgerAnalyticsController.class,
        ReservationAnalyticsController.class})
```

클래스 주석의 "세 컨트롤러가 같은 문구를 쓴다"도 "네 컨트롤러가"로 고친다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew test --tests 'com.jhg.wms.web.ReservationAnalyticsControllerTest'`
Expected: 3개 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/ReservationAnalyticsController.java \
        src/main/java/com/jhg/wms/web/AnalyticsErrorAdvice.java \
        src/test/java/com/jhg/wms/web/ReservationAnalyticsControllerTest.java
git commit -m "feat(wms): 체류 조회를 REST로 열고 400 평문 계약에 등록한다"
```

---

### Task 4: MCP 클라이언트와 도구 둘

**Files:**
- Modify: `mcp-server/wms_mcp/client.py`
- Modify: `mcp-server/wms_mcp/server.py`
- Test: `mcp-server/tests/test_client.py`

**Interfaces:**
- Consumes: Task 3의 `GET /api/analytics/reservation-dwell`, `GET /api/analytics/reservation-dwell-by-product`
- Produces: `client.get_reservation_dwell(from_date, to_date) -> dict`, `client.get_reservation_dwell_by_product(from_date, to_date) -> list`, MCP 도구 `reservation_dwell`, `reservation_dwell_by_product`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`mcp-server/tests/test_client.py` 끝에 추가:

```python
def test_체류_도구가_집계_경로로_부른다(monkeypatch):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, request=request,
                              json={"basis": "endedAt", "stillOpen": 0,
                                    "excludedMissingCreatedAt": 0})

    monkeypatch.setattr(client, "_build_client",
                        lambda: httpx.Client(transport=httpx.MockTransport(handler),
                                             base_url="http://wms.test"))

    result = client.get_reservation_dwell("2026-09-01", "2026-09-30")

    assert "/api/analytics/reservation-dwell" in seen["url"]
    assert "from=2026-09-01" in seen["url"]
    assert "to=2026-09-30" in seen["url"]
    assert result["basis"] == "endedAt"


def test_체류_상품별_도구가_제_경로로_부른다(monkeypatch):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, request=request, json=[])

    monkeypatch.setattr(client, "_build_client",
                        lambda: httpx.Client(transport=httpx.MockTransport(handler),
                                             base_url="http://wms.test"))

    client.get_reservation_dwell_by_product("2026-09-01", "2026-09-30")

    # 집계 경로의 접두사이므로 정확히 구분되는지 확인한다
    assert "/api/analytics/reservation-dwell-by-product" in seen["url"]


def test_체류_도구도_366일_상한에_걸린다():
    with pytest.raises(client.WmsError) as e:
        client.get_reservation_dwell("2020-01-01", "2026-09-30")

    assert "366" in str(e.value)
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd mcp-server && uv run pytest tests/test_client.py -k 체류 -v`
Expected: FAIL — `AttributeError: module 'wms_mcp.client' has no attribute 'get_reservation_dwell'`

- [ ] **Step 3: 클라이언트 함수를 더한다**

`client.py` 끝(`get_inventory_ledger` 아래)에 추가:

```python
def get_reservation_dwell(from_date: str, to_date: str) -> dict:
    return _get("/api/analytics/reservation-dwell", from_date, to_date)


def get_reservation_dwell_by_product(from_date: str, to_date: str) -> list:
    return _get("/api/analytics/reservation-dwell-by-product", from_date, to_date)
```

- [ ] **Step 4: MCP 도구를 더한다**

`server.py`의 `inventory_ledger` 아래, `main()` 위에 추가:

```python
@mcp.tool()
def reservation_dwell(from_date: str, to_date: str) -> dict:
    """기간 내 끝난 예약이 재고를 붙들고 있던 시간의 분포.

    체류는 예약 생성부터 종료까지다. 구간 판정은 종료 시각(basis="endedAt")이지 생성 시각이 아니다.
    shipped와 released는 따로 온다 — 합쳐 읽지 마라. 출고 체류는 정상 처리에 걸린 시간이고
    해제 체류는 헛되이 묶여 있던 시간이라 조치할 곳이 다르다.
    count가 0이면 median·p90·max는 null이다. 0분과 잴 것이 없는 것은 다르다.
    stillOpen은 구간 끝에 아직 안 끝난 예약 수이고 생존 편향의 크기다 — 오래 붙들린 예약일수록
    아직 안 끝나 이 분포에 안 잡히므로, 이 수를 밝히지 않고 중앙값을 인용하지 마라.
    excludedMissingCreatedAt은 생성 시각이 없어 잴 수 없었던 예약 수다. 보고서는 그 수를 밝혀라.
    단위는 분이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_reservation_dwell(from_date, to_date))


@mcp.tool()
def reservation_dwell_by_product(from_date: str, to_date: str) -> list:
    """상품별 체류 묶음. 반복해서 오래 붙들린 상품이 먼저 온다.

    집계 도구와 같은 모수다(기간 내 끝난 예약, 생성 시각이 있는 것만).
    occurrences는 그 상품이 든 예약 중 체류를 잰 건수다 — 예약 하나가 상품 여럿을 담으면
    담은 수만큼 계상되므로 occurrences의 합은 예약 건수가 아니다.
    한 번 아주 오래 걸린 것보다 여러 번 반복해서 오래 걸리는 쪽이 로케이션·재고 부족 같은
    구조적 원인을 가리킨다 — 정렬이 그 순서다.
    빈 목록은 오류가 아니라 그 기간에 끝난 예약이 없었다는 뜻이다.
    단위는 분이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_reservation_dwell_by_product(from_date, to_date))
```

같은 커밋에서 `server.py` 모듈 docstring 첫 줄의 낡은 수를 고친다 — 도구가 넷이던 시절의 문구이고 지금은 아홉이다:

```python
"""WMS 분석 MCP 서버 — 읽기 전용 도구 아홉.
```

- [ ] **Step 5: 통과를 확인한다**

Run: `cd mcp-server && uv run pytest tests/ -v`
Expected: 신규 3개 포함 전부 PASS.

- [ ] **Step 6: 커밋**

```bash
git add mcp-server/wms_mcp/client.py mcp-server/wms_mcp/server.py mcp-server/tests/test_client.py
git commit -m "feat(wms): 체류 도구 둘을 MCP에 붙인다"
```

---

### Task 5: 읽는 규율 스킬과 문서

**Files:**
- Create: `.claude/skills/wms-reservation-dwell-report/SKILL.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 4의 도구 `reservation_dwell`, `reservation_dwell_by_product`와 그 응답 필드 이름
- Produces: 없음 (문서)

- [ ] **Step 1: 스킬을 쓴다**

`.claude/skills/wms-reservation-dwell-report/SKILL.md` 신규. 프런트매터는 기존 두 스킬과 같은 모양이다:

```markdown
---
name: wms-reservation-dwell-report
description: Use when writing, reviewing, or summarizing a WMS 예약 체류(reservation dwell) report, or whenever quoting dwell times, ship-vs-release paths, or per-product dwell obtained from the wms MCP tools (reservation_dwell, reservation_dwell_by_product).
---

# WMS 예약 체류 보고서

숫자는 WMS가 이미 냈다. 이 문서가 정하는 것은 **그 숫자를 어떻게 읽고 무엇을 쓸지**다.

## 도구 셋 (읽기 전용, 날짜는 `YYYY-MM-DD`, 구간은 최대 366일)

| 도구 | 인자 | 돌려주는 것 |
|---|---|---|
| `reservation_dwell` | `from_date`, `to_date` | 출고·해제 경로별 체류 분포 + `stillOpen` + `excludedMissingCreatedAt` |
| `reservation_dwell_by_product` | `from_date`, `to_date` | 상품별 체류 묶음 (반복 횟수 순) |

요청에 기간이 없으면 **임의로 고르지 말고 묻는다.** 도구는 어느 구간에 데이터가 있는지
알려주지 않으므로 "지난달" 같은 기본값은 빈 구간을 짚을 수 있다. 넓은 창으로 먼저 훑었더라도
묻는다 — 확인한 것은 데이터가 어디 있는지지, 사용자가 어느 구간을 보려는지가 아니다.

## 필드의 정의

**체류는 예약 생성부터 종료까지다.** 단위는 분이다. 종료는 출고면 송장 발급 시각,
해제면 해제 시각이다. 배송 완료 시각은 쓰지 않는다 — 재고는 출고 시점에 이미 나갔다.

**구간 판정은 종료 시각이다**(`basis: "endedAt"`). 실사의 `createdAt` 기준과 다르다.
기간 내에 *생성된* 예약이 아니라 기간 내에 *끝난* 예약이다.

**`count`가 0이면 `medianMinutes`·`p90Minutes`·`maxMinutes`는 `null`이다.**
0분으로 읽지 마라 — 잴 것이 없는 것과 0분 만에 끝난 것은 다르다.

**분위수는 보간하지 않은 실측치(nearest-rank)다.** 표본에 실제로 있는 값이다.

**`occurrences`의 합은 예약 건수가 아니다.** 예약 하나가 상품 여럿을 담으면 담은 수만큼 계상된다.

## 보고서에 반드시 들어가는 것

1. **표본 크기** — 경로별 `count`를 먼저 밝힌다. 몇 건짜리 중앙값인지 모르면 그 값은 못 읽는다.
2. **`stillOpen`** — 구간 끝에 아직 안 끝난 예약 수. **생존 편향의 크기**다. 오래 붙들려 있는
   예약일수록 아직 안 끝나 분포에 안 잡히므로, 이 수를 밝히지 않고 중앙값을 인용하면
   실제보다 짧게 말하는 것이다. 이 수가 표본보다 크면 분포를 믿을 수 없다고 쓴다.
3. **`excludedMissingCreatedAt`** — 생성 시각이 없어 못 잰 예약 수. 0이 아니면 밝힌다.

## 판단 기준

- **출고 체류와 해제 체류를 한 문장에 섞지 않는다.** 출고 체류가 길면 출고 작업이 밀린 것이고,
  해제 체류가 길면 재고가 헛되이 묶여 있던 것이다. 조치할 곳이 다르다.
- **없는 기준을 만들지 않는다.** WMS는 목표 체류시간도 SLA도 업계 벤치마크도 갖고 있지 않다.
  **"보통 24시간 이내"처럼 데이터에 없는 수치를 끌어와 잘하고 못함을 규정하지 마라.**
  값은 그 자체로 보고하고, 비교는 같은 도구로 뽑은 **다른 기간의 실측치**로만 한다.
  비교할 이전 구간이 없으면 없다고 쓴다.
- **중앙값과 p90을 같이 읽는다.** 체류는 꼬리가 긴 분포다. 중앙값만 쓰면 오래 걸린 건들이 사라진다.
- **체류가 긴 이유를 단정하지 않는다.** 도구는 얼마나 걸렸는지만 안다. 재고 부족인지 작업 지연인지
  주문 취소가 늦은 것인지는 이 데이터로 가르지 못한다. 확인할 것을 제안하는 데서 멈춘다.
- **금액으로 환산하지 않는다.** WMS에는 수량뿐이다. 기회손실·재고비용은 OMS 소관이다.
- **빈 목록은 오류가 아니다.** 그 기간에 끝난 예약이 없었다는 뜻이다.

## 원장과 겹쳐 읽지 마라

**예약은 `inventory_ledger`에 나타나지 않는다.** 예약 생성은 `onHand`를 바꾸지 않아 원장에
행을 남기지 않는다. 원장에서 그 예약을 못 찾았다고 "이동이 없었다"고 쓰지 마라 — 애초에
보이지 않는 것이다. 원장에 보이는 것은 출고(SHIP)뿐이고, 그것은 체류가 *끝난* 시점이다.

## 흔한 실수

| 실수 | 실제 |
|---|---|
| 출고와 해제를 합친 중앙값을 쓴다 | 조치할 곳이 다른 두 이야기를 뭉갠 값이다 |
| `stillOpen`을 안 밝히고 중앙값을 인용한다 | 오래 붙들린 예약이 빠진 값이다. 실제보다 짧게 말하는 것 |
| `count: 0`의 `null`을 0분으로 읽는다 | 잴 것이 없다는 뜻이다 |
| "통상 24시간 이내가 정상"이라고 쓴다 | **그 기준은 데이터에 없다.** 지어낸 기준 위에 결론을 세우는 것 |
| `occurrences` 합을 예약 건수로 쓴다 | 예약 하나가 상품 여럿을 담으면 여러 번 계상된다 |
| 체류가 기니 재고 부족이라고 쓴다 | 도구는 얼마나 걸렸는지만 안다. 왜는 모른다 |
| 원장에 없으니 예약이 없었다고 쓴다 | 예약은 원장에 애초에 안 남는다 |
```

- [ ] **Step 2: `mcp-server/README.md`의 도구 표를 고친다**

도구 표는 루트 `README.md`가 아니라 여기 있다. 세 군데를 고친다:

1. 15행 제목 `## 도구 일곱 (전부 읽기 전용)` → `## 도구 아홉 (전부 읽기 전용)`
2. 113행 `도구 일곱이 보입니다` → `도구 아홉이 보입니다`
3. 표 끝(`inventory_ledger` 행 아래)에 두 행을 더한다:

```markdown
| `reservation_dwell` | `from_date`, `to_date` |
| `reservation_dwell_by_product` | `from_date`, `to_date` |
```

표 아래 설명 문단에 한 줄을 더한다:

```markdown
체류 도구 둘은 **기간 내 끝난 예약만** 모수로 삼습니다(`basis="endedAt"`). 아직 안 끝난 예약은
분포에 없고 그 수가 `stillOpen`으로 옵니다 — 오래 붙들린 예약일수록 아직 안 끝나 빠지므로,
그 수를 밝히지 않은 중앙값은 실제보다 짧습니다.
```

- [ ] **Step 3: 루트 `README.md`에 스킬을 얹는다**

"실사 보고서는 별도 Skill입니다 —" 문단(398~403행 근처) 바로 아래에 같은 모양으로 더한다:

```markdown
예약 체류 보고서도 별도 Skill입니다 —
[`.claude/skills/wms-reservation-dwell-report/SKILL.md`](.claude/skills/wms-reservation-dwell-report/SKILL.md).
판단 기준이 또 다릅니다: **출고 체류와 해제 체류를 섞지 않기**(조치할 곳이 다르다),
**`stillOpen`을 밝히지 않은 중앙값은 쓰지 않기**(생존 편향), 그리고 예약은 `onHand`를 바꾸지 않아
**원장에 아예 안 남는다**는 사실이 들어갑니다.
```

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

Run: `cd mcp-server && uv run pytest tests/ -v`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add .claude/skills/wms-reservation-dwell-report/SKILL.md README.md mcp-server/README.md
git commit -m "docs: 체류 도구를 스킬과 README에 반영한다"
```

---

## 완료 확인

- [ ] `./gradlew test` 전체 통과
- [ ] `cd mcp-server && uv run pytest tests/` 전체 통과
- [ ] 앱을 재시작하고 실제 호출로 확인한다 — 새 컨트롤러는 재시작 없이는 안 올라온다:

```bash
curl -s -u wms:wms "http://localhost:8081/api/analytics/reservation-dwell?from=2026-08-01&to=2026-09-05"
curl -s -u wms:wms "http://localhost:8081/api/analytics/reservation-dwell-by-product?from=2026-08-01&to=2026-09-05"
```

기대: 200과 `basis: "endedAt"`을 포함한 JSON. 404가 나오면 앱이 옛 빌드다.
