package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReturnClassificationRepository;
import com.jhg.wms.repository.RmaReturnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 집계를 실제 PostgreSQL에서 검증한다. API 호출이 없으므로 전 구간이 공짜다.
 *
 * 원장 행의 createdAt은 InventoryTransaction.of()가 now()로 박는다. 과거 날짜 출고를
 * 만들려면 그 필드를 저장 전에 바꿔야 하는데, 이걸 위해 운영 코드에 setter를 여는 것은
 * 테스트 편의를 위해 도메인을 무르게 만드는 일이다. 리플렉션으로 테스트 안에서만 처리한다.
 */
@DataJpaTest
class ReturnAnalyticsServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired RmaReturnRepository rmaRepo;
    @Autowired ReturnClassificationRepository classificationRepo;

    ReturnAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new ReturnAnalyticsService(txnRepo, rmaRepo, classificationRepo, inventoryRepo);
    }

    private void 재고(Long productId, String name) {
        inventoryRepo.save(Inventory.create(productId, name, 100));
    }

    private void 출고(Long productId, int qty, String reference, LocalDate when) {
        InventoryTransaction t = InventoryTransaction.of(productId, InventoryTransactionType.SHIP,
                -qty, 100, 100 - qty, reference, null, "tester");
        ReflectionTestUtils.setField(t, "createdAt", when.atTime(12, 0));
        txnRepo.save(t);
    }

    private RmaReturn 반품(Long orderId, Long productId, int qty, String reason) {
        RmaReturn r = RmaReturn.create("RK-" + orderId + "-" + productId, orderId, reason);
        r.addItem(1L, productId, qty);
        return rmaRepo.save(r);
    }

    @Test
    void 기간_밖_출고는_분모에_들어가지_않는다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));   // 기간 안
        출고(1L, 90, "ORDER#101", LocalDate.of(2026, 2, 10));   // 기간 밖
        반품(100L, 1L, 2, "파손됐어요");

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).shippedQty()).isEqualTo(10);
        assertThat(report.rows().get(0).returnedQty()).isEqualTo(2);
        assertThat(report.rows().get(0).returnRate()).isEqualTo(0.2);
    }

    @Test
    void 취소된_반품은_분자에서_빠진다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        RmaReturn 취소됨 = 반품(100L, 1L, 5, "역시 안 보낼게요");
        취소됨.cancel();
        rmaRepo.save(취소됨);

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows().get(0).returnedQty()).isZero();
    }

    // 조용히 빠지면 분모가 줄어 반품률이 과대평가된다. 세어서 드러낸다.
    @Test
    void 주문_연결이_안_되는_출고행을_따로_센다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 40, "수동출고", LocalDate.of(2026, 3, 11));
        출고(1L, 50, null, LocalDate.of(2026, 3, 12));

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.unlinkedShipRows()).isEqualTo(2);
        assertThat(report.rows().get(0).shippedQty()).isEqualTo(10);
    }

    // 코호트의 성숙도는 기간의 성질이다. 경과 7일짜리 1%와 경과 60일짜리 1%는 다른 수다.
    @Test
    void 관찰_경과일은_기간_종료일부터_오늘까지다() {
        재고(1L, "상품 1");
        LocalDate 종료 = LocalDate.now().minusDays(10);
        출고(1L, 10, "ORDER#100", 종료.minusDays(1));

        var report = service.productReturnRates(종료.minusDays(30), 종료);

        assertThat(report.observedDays()).isEqualTo(10);
    }
}
