package com.jhg.wms.service;

import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 추적 조회.
 *
 * <p>가장 중요한 판단 둘. (1) 행은 <b>시간 오름차순</b>이다 — beforeQty→afterQty 사슬이
 * 이어붙어야 "여기서 끊겼다"가 보인다. (2) 잘랐으면 잘랐다고 말한다 — 조용히 자르면
 * 모델이 받은 것을 전량으로 읽고 "그 사이 이동이 없었다"고 쓴다.
 */
@DataJpaTest
class InventoryLedgerAnalyticsServiceTest {

    @Autowired InventoryTransactionRepository repository;
    InventoryLedgerAnalyticsService service;

    private static final Long PRODUCT = 11L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 3);

    @BeforeEach
    void setUp() {
        service = new InventoryLedgerAnalyticsService(repository);
    }

    /** createdAt은 팩토리가 now()로 박는다. 기간 테스트를 하려면 저장 전에 갈아끼운다. */
    private void save(Long productId, InventoryTransactionType type, int delta,
                      int beforeQty, int afterQty, String reference, String reason,
                      LocalDateTime at) {
        var txn = InventoryTransaction.of(productId, type, delta, beforeQty, afterQty,
                                          reference, reason, "manager1");
        ReflectionTestUtils.setField(txn, "createdAt", at);
        repository.saveAndFlush(txn);
    }

    @Test
    void 행을_시간_오름차순으로_낸다() {
        save(PRODUCT, InventoryTransactionType.SHIP, -3, 115, 112, "ORDER#34", null,
             LocalDateTime.of(2026, 9, 2, 10, 0));
        save(PRODUCT, InventoryTransactionType.RECEIVE, 5, 110, 115, "PO#7", null,
             LocalDateTime.of(2026, 9, 1, 9, 0));

        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).hasSize(2);
        // 오름차순이라야 115→112가 이어붙는다
        assertThat(report.rows().get(0).type()).isEqualTo(InventoryTransactionType.RECEIVE);
        assertThat(report.rows().get(1).type()).isEqualTo(InventoryTransactionType.SHIP);
        assertThat(report.truncated()).isFalse();
        assertThat(report.total()).isEqualTo(2);
    }

    @Test
    void 다른_상품과_기간_밖은_섞이지_않는다() {
        save(PRODUCT, InventoryTransactionType.ADJUST, -1, 10, 9, null, "파손",
             LocalDateTime.of(2026, 9, 2, 10, 0));
        save(99L, InventoryTransactionType.ADJUST, -1, 10, 9, null, "다른 상품",
             LocalDateTime.of(2026, 9, 2, 10, 0));
        save(PRODUCT, InventoryTransactionType.ADJUST, -1, 10, 9, null, "기간 밖",
             LocalDateTime.of(2026, 8, 31, 23, 59));

        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).reason()).isEqualTo("파손");
    }

    @Test
    void 종료일_당일의_이동도_들어온다() {
        // 경계다. to를 그대로 쓰면 종료일 하루가 통째로 빠진다.
        save(PRODUCT, InventoryTransactionType.COUNT, -2, 112, 110, "CC#8", null,
             LocalDateTime.of(2026, 9, 3, 17, 10));

        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).hasSize(1);
    }

    @Test
    void 이동이_없으면_빈_목록이고_오류가_아니다() {
        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).isEmpty();
        assertThat(report.truncated()).isFalse();
        assertThat(report.total()).isZero();
    }

    // 아래 두 상한 테스트는 실제 상한(500)이 아니라 낮춘 상한으로 같은 로직을 검증한다.
    // 500/501행을 실제로 물리 DB에 꽂으면 스위트 전체를 도는 동안 여러 테스트 컨텍스트가
    // 켜켜이 캐시된 채 같은 물리 DB(wms_test)를 공유하는 상황에서 시퀀스 경계를 넓게
    // 가로질러 NonUniqueObjectException을 낼 수 있다 — 상한을 낮추면 같은 코드 경로를
    // 훨씬 적은 insert로 증명할 수 있다.
    private static final int SMALL_CAP = 5;

    @Test
    void 상한을_넘기면_최근_행을_남기고_잘랐다고_말한다() {
        service = new InventoryLedgerAnalyticsService(repository, SMALL_CAP);
        for (int i = 0; i < SMALL_CAP + 1; i++) {
            save(PRODUCT, InventoryTransactionType.ADJUST, -1, 100 - i, 99 - i, null, "행 " + i,
                 LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(i));
        }

        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).hasSize(SMALL_CAP);
        assertThat(report.truncated()).isTrue();
        assertThat(report.total()).isEqualTo(SMALL_CAP + 1);
        // 오래된 쪽을 버린다 — 조사 중인 사건은 최근에 있다
        assertThat(report.rows().get(0).reason()).isEqualTo("행 1");
    }

    @Test
    void 정확히_상한이면_자르지_않는다() {
        // 경계다. `>=`로 쓰면 상한과 같은 개수여도 잘렸다고 거짓을 말한다.
        service = new InventoryLedgerAnalyticsService(repository, SMALL_CAP);
        for (int i = 0; i < SMALL_CAP; i++) {
            save(PRODUCT, InventoryTransactionType.ADJUST, -1, 100, 99, null, "행 " + i,
                 LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(i));
        }

        var report = service.ledger(PRODUCT, FROM, TO);

        assertThat(report.rows()).hasSize(SMALL_CAP);
        assertThat(report.truncated()).isFalse();
    }

    @Test
    void 운영_기본_상한은_500이다() {
        // 기본 생성자를 쓰면 DEFAULT_MAX_ROWS(500)가 적용된다는 것을 리플렉션으로 고정한다.
        // 이 상수가 바뀌면 이 테스트가 실패해야 한다.
        assertThat(InventoryLedgerAnalyticsService.DEFAULT_MAX_ROWS).isEqualTo(500);
        assertThat(ReflectionTestUtils.getField(service, "maxRows")).isEqualTo(500);
    }

    @Test
    void 행위자는_보고서에_담기지_않는다() {
        // LedgerRow에 actor 필드가 없다는 것이 이 설계의 핵심 제약이다.
        save(PRODUCT, InventoryTransactionType.ADJUST, -1, 10, 9, null, "파손",
             LocalDateTime.of(2026, 9, 2, 10, 0));

        var row = service.ledger(PRODUCT, FROM, TO).rows().get(0);

        assertThat(row.toString()).doesNotContain("manager1");
    }
}
