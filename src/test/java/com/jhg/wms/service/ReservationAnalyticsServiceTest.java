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
}
