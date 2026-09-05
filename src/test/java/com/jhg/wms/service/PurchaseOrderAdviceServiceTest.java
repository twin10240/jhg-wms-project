package com.jhg.wms.service;

import com.jhg.wms.client.OmsDeliveryNotifier;
import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * 원장의 createdAt은 InventoryTransaction.of()가 now()로 박는다. 과거 날짜 출고를 만들려면
 * 저장 전에 그 필드를 바꿔야 하는데, 그걸 위해 운영 코드에 setter를 여는 것은 테스트 편의로
 * 도메인을 무르게 만드는 일이다 — ReturnAnalyticsServiceTest와 같이 리플렉션으로 처리한다.
 */
@DataJpaTest
class PurchaseOrderAdviceServiceTest {

    private static final LocalDate 오늘 = LocalDate.of(2026, 9, 5);

    @Autowired InventoryRepository inventoryRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired PurchaseOrderRepository poRepo;
    @Autowired ReplenishmentRequestRepository requestRepo;
    @Autowired ReservationRepository reservationRepo;

    PurchaseOrderService purchaseOrderService;
    PurchaseOrderAdviceService service;

    @BeforeEach
    void setUp() {
        InventoryService inventoryService = new InventoryService(inventoryRepo, reservationRepo, txnRepo,
                mock(OmsReplenishmentNotifier.class), mock(OmsDeliveryNotifier.class), () -> "system");
        purchaseOrderService = new PurchaseOrderService(poRepo, inventoryService, requestRepo,
                mock(PurchaseOrderMemoClassificationTrigger.class));
        service = new PurchaseOrderAdviceService(txnRepo, inventoryService, purchaseOrderService);
    }

    private void 재고(Long productId, String name, int onHand) {
        inventoryRepo.save(Inventory.create(productId, name, onHand));
    }

    private void 원장(Long productId, InventoryTransactionType type, int delta, LocalDate when) {
        InventoryTransaction t = InventoryTransaction.of(productId, type, delta, 100, 100 + delta,
                "TEST", null, "tester");
        ReflectionTestUtils.setField(t, "createdAt", when.atTime(12, 0));
        txnRepo.save(t);
    }

    private ProductAdvice 상품(List<ProductAdvice> rows, Long productId) {
        return rows.stream().filter(r -> r.productId().equals(productId)).findFirst().orElseThrow();
    }

    @Test
    void 원장이_비면_빈_목록이다() {
        재고(1L, "상품 1", 50);
        assertThat(service.advise(오늘)).isEmpty();
    }

    @Test
    void 표본_일수가_창보다_짧으면_짧은_쪽으로_나눈다() {
        재고(1L, "상품 1", 24);
        // 원장이 10일 전(8/27)에 시작한다. 창(30일)이 아니라 이 10일이 분모여야 한다.
        원장(1L, InventoryTransactionType.OPENING, 100, 오늘.minusDays(9));
        원장(1L, InventoryTransactionType.SHIP, -102, 오늘.minusDays(5));

        ProductAdvice a = 상품(service.advise(오늘), 1L);

        assertThat(a.sampleDays()).isEqualTo(10);
        assertThat(a.shippedQty()).isEqualTo(102);              // SHIP 델타는 음수 — 부호를 뒤집어 낸다
        assertThat(a.dailyAverage()).isCloseTo(10.2, within(0.001));
        // 30일로 나눴다면 3.4/일 → 소진 7.06일이 되어 재고가 버틴다고 잘못 말한다.
        assertThat(a.daysToStockout()).isCloseTo(24 / 10.2, within(0.001));
    }

    @Test
    void 원장이_창보다_먼저_시작했으면_창_길이가_분모다() {
        재고(1L, "상품 1", 60);
        원장(1L, InventoryTransactionType.OPENING, 500, 오늘.minusDays(90));
        원장(1L, InventoryTransactionType.SHIP, -60, 오늘.minusDays(3));

        ProductAdvice a = 상품(service.advise(오늘), 1L);

        assertThat(a.sampleDays()).isEqualTo(PurchaseOrderAdviceService.WINDOW_DAYS);
        assertThat(a.dailyAverage()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void 창_밖_출고는_세지_않는다() {
        재고(1L, "상품 1", 60);
        원장(1L, InventoryTransactionType.OPENING, 500, 오늘.minusDays(90));
        원장(1L, InventoryTransactionType.SHIP, -60, 오늘.minusDays(40));

        assertThat(상품(service.advise(오늘), 1L).shippedQty()).isZero();
    }

    @Test
    void 출고가_없으면_소진_예상은_null이다() {
        재고(1L, "상품 1", 50);
        원장(1L, InventoryTransactionType.OPENING, 50, 오늘.minusDays(9));

        ProductAdvice a = 상품(service.advise(오늘), 1L);

        assertThat(a.dailyAverage()).isZero();
        // 0일이 아니다 — 오늘 소진된다는 뜻이 되어버린다. 잴 것이 없는 것과 다르다.
        assertThat(a.daysToStockout()).isNull();
    }

    @Test
    void 출고_외의_원장은_소모량에_들어가지_않는다() {
        재고(1L, "상품 1", 50);
        원장(1L, InventoryTransactionType.OPENING, 100, 오늘.minusDays(9));
        원장(1L, InventoryTransactionType.RETURN, 5, 오늘.minusDays(2));
        원장(1L, InventoryTransactionType.ADJUST, -30, 오늘.minusDays(1));

        assertThat(상품(service.advise(오늘), 1L).shippedQty()).isZero();
    }

    @Test
    void 소진_예상이_없는_상품은_맨_뒤로_간다() {
        재고(1L, "느린 상품", 100);
        원장(1L, InventoryTransactionType.OPENING, 100, 오늘.minusDays(9));
        원장(1L, InventoryTransactionType.SHIP, -10, 오늘.minusDays(1));   // 1/일 → 100일

        재고(2L, "급한 상품", 10);
        원장(2L, InventoryTransactionType.OPENING, 10, 오늘.minusDays(9));
        원장(2L, InventoryTransactionType.SHIP, -50, 오늘.minusDays(1));   // 5/일 → 2일

        재고(3L, "안 움직인 상품", 999);
        원장(3L, InventoryTransactionType.OPENING, 999, 오늘.minusDays(9));

        assertThat(service.advise(오늘))
                .extracting(ProductAdvice::productId)
                .containsExactly(2L, 1L, 3L);
    }

    @Test
    void 직전_발주는_가장_최근_것을_상품별로_붙인다() {
        재고(1L, "상품 1", 50);
        원장(1L, InventoryTransactionType.OPENING, 50, 오늘.minusDays(9));

        Long 예전 = purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 10)), "예전");
        Long 최근 = purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 40)), "최근");
        ReflectionTestUtils.setField(poRepo.findById(예전).orElseThrow(), "createdAt",
                오늘.minusDays(20).atTime(9, 0));
        poRepo.flush();

        var lastOrder = 상품(service.advise(오늘), 1L).lastOrder();

        assertThat(lastOrder.purchaseOrderId()).isEqualTo(최근);
        assertThat(lastOrder.quantity()).isEqualTo(40);
        assertThat(lastOrder.leadTimeDays()).isNull();   // 아직 입고 전
    }
}
