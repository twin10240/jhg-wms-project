package com.jhg.wms.service;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실사 분석 조회.
 *
 * <p>가장 중요한 판단은 <b>정확도의 분모</b>다. 반려된 세션은 "계수를 신뢰할 수 없다"고
 * 사람이 판정한 것이라 정확도에 넣으면 지표가 거짓이 된다. 대신 뺐다는 사실을 응답이
 * 함께 실어야 보고서가 그것을 밝힐 수 있다 — 조용히 빼면 분모를 속이는 것과 같다.
 */
@DataJpaTest
class CycleCountAnalyticsServiceTest {

    @Autowired CycleCountRepository cycleCountRepository;
    @Autowired InventoryRepository inventoryRepository;
    CycleCountAnalyticsService service;

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        service = new CycleCountAnalyticsService(cycleCountRepository, inventoryRepository);
    }

    private void seedProduct(long productId, int onHand) {
        inventoryRepository.save(Inventory.create(productId, onHand));
    }

    /** items는 {productId, 장부수량, 실물수량} 묶음이다. */
    private void seedSession(LocalDate createdOn, CycleCountStatus finalStatus, int[]... items) {
        CycleCount c = CycleCount.open("operator", "테스트 실사");
        for (int[] it : items) c.addItem((long) it[0], it[1]);
        cycleCountRepository.saveAndFlush(c);

        for (int i = 0; i < items.length; i++)
            c.recordCount(c.getItems().get(i).getId(), items[i][2]);
        c.submit("operator");
        if (finalStatus == CycleCountStatus.APPROVED) c.approve("manager");
        if (finalStatus == CycleCountStatus.REJECTED) c.reject("manager", "계수 신뢰 어려움");

        // 구간 판정 기준을 통제한다. 실제 값은 open()이 now()로 박는다.
        ReflectionTestUtils.setField(c, "createdAt", createdOn.atTime(10, 0));
        cycleCountRepository.saveAndFlush(c);
    }

    @Test
    void 정확도는_승인된_세션의_항목만_센다() {
        seedProduct(1L, 10); seedProduct(2L, 10); seedProduct(3L, 10);
        // 승인: 3항목 중 2개 일치, 1개 부족
        seedSession(LocalDate.of(2026, 9, 5), CycleCountStatus.APPROVED,
                new int[]{1, 10, 10}, new int[]{2, 10, 10}, new int[]{3, 10, 8});
        // 반려: 2항목 전부 차이 — 정확도를 끌어내리면 안 된다
        seedSession(LocalDate.of(2026, 9, 6), CycleCountStatus.REJECTED,
                new int[]{1, 10, 3}, new int[]{2, 10, 20});

        var report = service.accuracy(FROM, TO);

        assertThat(report.countedItems()).isEqualTo(3);
        assertThat(report.matchedItems()).isEqualTo(2);
        assertThat(report.accuracy()).isEqualTo(2.0 / 3);
        // 뺀 사실을 밝히지 않으면 분모를 속이는 것이다
        assertThat(report.excludedRejectedItems()).isEqualTo(2);
        assertThat(report.sessions().approved()).isEqualTo(1);
        assertThat(report.sessions().rejected()).isEqualTo(1);
        assertThat(report.sessions().total()).isEqualTo(2);
    }

    @Test
    void 차이를_과다와_부족으로_나눈다() {
        seedProduct(1L, 10); seedProduct(2L, 10);
        seedSession(LocalDate.of(2026, 9, 5), CycleCountStatus.APPROVED,
                new int[]{1, 10, 7},    // 부족 3
                new int[]{2, 10, 12});  // 과다 2

        var report = service.accuracy(FROM, TO);

        assertThat(report.underItems()).isEqualTo(1);
        assertThat(report.underQty()).isEqualTo(3);
        assertThat(report.overItems()).isEqualTo(1);
        assertThat(report.overQty()).isEqualTo(2);
    }

    @Test
    void 반복해서_차이_난_상품이_먼저_온다() {
        seedProduct(1L, 10); seedProduct(2L, 10);
        seedSession(LocalDate.of(2026, 9, 5), CycleCountStatus.APPROVED,
                new int[]{1, 10, 9}, new int[]{2, 10, 4});
        seedSession(LocalDate.of(2026, 9, 8), CycleCountStatus.APPROVED,
                new int[]{1, 10, 9});

        List<CycleCountAnalyticsService.ProductVariance> rows = service.variances(FROM, TO);

        // 상품 2가 한 번에 -6으로 더 크지만, 반복이 먼저다 — 반복은 로케이션·라벨 문제를 가리킨다
        assertThat(rows.get(0).productId()).isEqualTo(1L);
        assertThat(rows.get(0).occurrences()).isEqualTo(2);
        assertThat(rows.get(0).netQty()).isEqualTo(-2);
        assertThat(rows).hasSize(2);
    }

    @Test
    void 반려_세션의_차이는_목록에도_넣지_않는다() {
        seedProduct(1L, 10);
        seedSession(LocalDate.of(2026, 9, 5), CycleCountStatus.REJECTED, new int[]{1, 10, 1});

        assertThat(service.variances(FROM, TO)).isEmpty();
    }

    @Test
    void 구간_밖_실사는_세지_않는다() {
        seedProduct(1L, 10);
        seedSession(LocalDate.of(2026, 8, 31), CycleCountStatus.APPROVED, new int[]{1, 10, 5});

        var report = service.accuracy(FROM, TO);

        assertThat(report.sessions().total()).isZero();
        assertThat(report.countedItems()).isZero();
    }

    @Test
    void 실사가_하나도_없으면_정확도는_0이_아니라_null이다() {
        // 0.0으로 내면 "전부 틀렸다"로 읽힌다. 잴 것이 없는 것과 다 틀린 것은 다르다.
        var report = service.accuracy(FROM, TO);

        assertThat(report.countedItems()).isZero();
        assertThat(report.accuracy()).isNull();
    }
}
