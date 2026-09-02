package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.domain.ReturnOwnerArea;
import com.jhg.wms.domain.RmaDisposition;
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
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** 한 반품에 상품이 여러 개 든 형태. RmaService가 요청 품목마다 addItem을 부르므로 운영에 실재한다. */
    private RmaReturn 반품_다품목(Long orderId, String reason, Map<Long, Integer> qtyByProduct) {
        RmaReturn r = RmaReturn.create("RK-" + orderId + "-multi", orderId, reason);
        qtyByProduct.forEach((productId, qty) -> r.addItem(1L, productId, qty));
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

    // 정렬 비교자가 두 키를 체인했을 때 .reversed()의 방향이 맞아야 한다.
    // 기존 테스트는 전부 상품 하나만 다뤄서, 역순이 뒤집혀도 통과한다.
    // 정렬은 이 클래스에서 조용히 틀릴 수 있는 유일한 자리다.
    @Test
    void 반품률이_높은_순으로_정렬되고_동률이면_반품수량이_많은_순이다() {
        재고(1L, "상품 A");
        재고(2L, "상품 B");
        재고(3L, "상품 C");

        // 상품 A: 반품률 0.20 (100 출고 중 20 반품)
        출고(1L, 100, "ORDER#100", LocalDate.of(2026, 3, 10));
        반품(100L, 1L, 20, "파손");

        // 상품 B: 반품률 0.20 (50 출고 중 10 반품) — A와 동률이지만 반품수량이 적음
        출고(2L, 50, "ORDER#101", LocalDate.of(2026, 3, 10));
        반품(101L, 2L, 10, "파손");

        // 상품 C: 반품률 0.05 (1000 출고 중 50 반품) — 반품률은 가장 낮은데 반품수량은 가장 많다.
        // 두 키를 일부러 반대로 놓는다. 상관돼 있으면 주 정렬 키가 반품수량으로 바뀌어도
        // 같은 순서가 나와 테스트가 통과한다 — 그때 리포트는 "위험한 순"이 아니라 "물량 순"이 된다.
        출고(3L, 1000, "ORDER#102", LocalDate.of(2026, 3, 10));
        반품(102L, 3L, 50, "파손");

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows()).hasSize(3);
        assertThat(report.rows()).extracting(ReturnAnalyticsService.ProductReturnRate::productId)
                .containsExactly(1L, 2L, 3L);
    }

    private void 분류(Long rmaReturnId, ReturnCategory category) {
        classificationRepo.save(ReturnClassification.create(rmaReturnId, category, Confidence.HIGH,
                "근거", RmaDisposition.RESTOCKED, "claude-haiku-4-5-20251001", 100, 10));
    }

    @Test
    void 범주별_건수에_소관이_함께_나온다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(101L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.counts())
                .extracting(ReturnAnalyticsService.CategoryCount::category,
                            ReturnAnalyticsService.CategoryCount::ownerArea,
                            ReturnAnalyticsService.CategoryCount::count)
                .contains(org.assertj.core.groups.Tuple.tuple(
                                ReturnCategory.WRONG_ITEM, ReturnOwnerArea.PICKING, 1),
                          org.assertj.core.groups.Tuple.tuple(
                                ReturnCategory.DAMAGED, ReturnOwnerArea.PACKAGING, 1));
    }

    // 숨기면 합계가 안 맞고, 분류된 몇 건짜리 분포를 전체의 그림으로 읽게 된다.
    @Test
    void 분류가_없는_반품은_미분류로_따로_센다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.unclassified()).isEqualTo(1);
        assertThat(breakdown.totalReturns()).isEqualTo(2);
    }

    @Test
    void 코호트_밖_반품은_범주_분포에_들어가지_않는다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#900", LocalDate.of(2026, 1, 10));   // 기간 밖 출고
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(900L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.totalReturns()).isEqualTo(1);
    }

    @Test
    void 상품의_사유_원문을_코호트_안에서만_모은다() {
        재고(1L, "상품 1");
        재고(2L, "상품 2");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(2L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#900", LocalDate.of(2026, 1, 10));   // 기간 밖
        반품(100L, 1L, 1, "뚜껑이 헐거워요");
        반품(101L, 2L, 1, "다른 상품이 왔어요");
        반품(900L, 1L, 1, "기간 밖 반품");

        var entries = service.detailsByProduct(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).reason()).isEqualTo("뚜껑이 헐거워요");
        assertThat(entries.get(0).orderId()).isEqualTo(100L);
    }

    // 미분류를 빼면 원문 묶음이 분류된 것만 남아, 읽는 쪽이 전체를 봤다고 착각한다.
    @Test
    void 미분류_반품도_원문에_포함되고_범주는_비어_있다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var entries = service.detailsByProduct(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.reason()).isEqualTo("그냥요");
            assertThat(e.category()).isNull();
        });
    }
    // 반품 건수는 이 화면의 머리 숫자이고, 그 값은 @EntityGraph의 fetch join이 루트를 중복
    // 제거해 준다는 데 기대고 있다. 품목이 둘이면 조인 결과가 두 행인데 반품은 한 건이다.
    // 저장소 어디에도 다품목 반품 테스트가 없어 이 성질이 무검증이었다.
    @Test
    void 한_반품에_상품이_둘이어도_반품_건수는_하나다() {
        재고(1L, "상품 1");
        재고(2L, "상품 2");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(2L, 20, "ORDER#100", LocalDate.of(2026, 3, 10));
        반품_다품목(100L, "둘 다 문제였어요", new LinkedHashMap<>(Map.of(1L, 3, 2L, 4)));

        var breakdown = service.categoryBreakdown(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(breakdown.totalReturns()).isEqualTo(1);
        assertThat(breakdown.unclassified()).isEqualTo(1);
    }

    // 같은 반품이라도 수량은 품목마다 다르다. 반품 전체 수량을 두 상품에 똑같이 붙이면
    // 두 상품의 반품률이 함께 틀린다.
    @Test
    void 한_반품에_상품이_둘이면_수량이_상품별로_따로_붙는다() {
        재고(1L, "상품 1");
        재고(2L, "상품 2");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(2L, 20, "ORDER#100", LocalDate.of(2026, 3, 10));
        반품_다품목(100L, "둘 다 문제였어요", new LinkedHashMap<>(Map.of(1L, 3, 2L, 4)));

        var report = service.productReturnRates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(report.rows())
                .extracting(ReturnAnalyticsService.ProductReturnRate::productId,
                            ReturnAnalyticsService.ProductReturnRate::returnedQty,
                            ReturnAnalyticsService.ProductReturnRate::returnRate)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 3, 0.3),
                                 org.assertj.core.groups.Tuple.tuple(2L, 4, 0.2));

        // 원문 조회도 같은 품목 단위여야 한다 — 상품별로 한 건씩, 그 상품의 수량으로.
        assertThat(service.detailsByProduct(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .singleElement()
                .extracting(ReturnAnalyticsService.ReturnDetailRow::requestedQuantity)
                .isEqualTo(3);
        assertThat(service.detailsByProduct(2L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .singleElement()
                .extracting(ReturnAnalyticsService.ReturnDetailRow::requestedQuantity)
                .isEqualTo(4);
    }

    @Test
    void 범주_축은_그_범주의_반품만_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        분류(반품(101L, 1L, 1, "깨져서 왔어요").getId(), ReturnCategory.DAMAGED);

        var rows = service.detailsByCategory(ReturnCategory.WRONG_ITEM,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement()
                .extracting(ReturnAnalyticsService.ReturnDetailRow::reason)
                .isEqualTo("다른 색이 왔어요");
    }

    // 미분류를 별도 메서드로 두지 않고 category=null로 받는다. 화면에서 미분류는
    // 범주 표의 다섯 번째 행이라, 같은 링크 구조로 열려야 한다.
    @Test
    void 범주_축에_null을_주면_미분류만_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        출고(1L, 10, "ORDER#101", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 1, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);
        반품(101L, 1L, 1, "그냥요");

        var rows = service.detailsByCategory(null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo("그냥요");
            assertThat(row.category()).isNull();
            assertThat(row.confidence()).isNull();
        });
    }

    // 신뢰도를 내는 이유는 1회차 평가가 "confidence가 제 역할을 하는가"에 답하지 못했기
    // 때문이다. 틀린 분류가 실제로 LOW를 받았는지 화면에서 보여야 운영 데이터로 답한다.
    @Test
    void 분류된_반품은_신뢰도와_상품명을_함께_낸다() {
        재고(1L, "상품 1");
        출고(1L, 10, "ORDER#100", LocalDate.of(2026, 3, 10));
        분류(반품(100L, 1L, 2, "다른 색이 왔어요").getId(), ReturnCategory.WRONG_ITEM);

        var rows = service.detailsByProduct(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.productName()).isEqualTo("상품 1");
            assertThat(row.category()).isEqualTo(ReturnCategory.WRONG_ITEM);
            assertThat(row.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(row.requestedQuantity()).isEqualTo(2);
        });
    }

}
