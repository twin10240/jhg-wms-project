package com.jhg.wms.service;

import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.web.InventoryRowResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 발주 화면에 띄우는 근거. 무엇을 발주할지는 사람이 정하고, 여기서는 그 판단에 쓸 실측치만 낸다.
 *
 * <p>AI가 없다. 상품별 소모량은 원장 SHIP 델타의 합이라 산술이지 추론이 아니다 —
 * 이미 정확한 숫자를 모델에 다시 만들게 하면 틀려도 알아채지 못한다.
 *
 * <p>경계 규약은 {@link InventoryLedgerAnalyticsService}와 같다({@code LocalDateTime} 반개구간).
 * {@code InventoryTransaction.createdAt}이 {@code LocalDateTime}이라
 * {@link ReservationAnalyticsService}의 Asia/Seoul 변환은 여기 필요 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderAdviceService {

    /** 소모량을 재는 기본 창(오늘 포함). 원장이 이보다 늦게 시작한 상품은 시작일부터만 센다. */
    static final int WINDOW_DAYS = 30;

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryService inventoryService;
    private final PurchaseOrderService purchaseOrderService;

    /** 직전 발주. 취소된 발주도 담는다 — "직전 발주가 취소됐다"도 발주 판단에 쓰이는 사실이다. */
    public record LastOrder(Long purchaseOrderId, PurchaseOrderStatus status,
                            LocalDate orderedOn, int quantity,
                            LocalDate receivedOn, Long leadTimeDays) {}

    /**
     * @param sampleDays 일평균의 분모. 창 길이가 아니라 그 상품의 원장이 실제로 존재한 일수다 —
     *                   10일치를 30으로 나누면 일평균이 1/3이 되고 소진 예상일이 3배 길어진다.
     *                   재고가 버틸 거라고 잘못 말하는 쪽이라 조용히 틀리면 안 된다.
     * @param dailyAverage 하루 평균 출고량. 원장이 없거나 출고가 없으면 0.
     * @param daysToStockout 가용재고 ÷ 일평균. <b>일평균이 0이면 null이다</b> — 0일이 아니라
     *                       잴 것이 없다는 뜻이다(체류·반품 도구가 {@code count: 0}에 null을 내는 것과 같다).
     */
    public record ProductAdvice(Long productId, String productName,
                                int shippedQty, long sampleDays, double dailyAverage,
                                int onHandQty, int reservedQty, int availableQty,
                                Double daysToStockout, LastOrder lastOrder) {}

    /**
     * 소진이 임박한 순으로 낸다. 소진 예상일이 없는 상품(출고 0)은 맨 뒤다.
     *
     * @return 원장이 한 행도 없으면 빈 목록 — 화면은 이때 표를 통째로 렌더하지 않는다.
     */
    public List<ProductAdvice> advise(LocalDate today) {
        Map<Long, LocalDate> firstRecordedAt = firstRecordedAt();
        if (firstRecordedAt.isEmpty()) return List.of();

        LocalDate windowFrom = today.minusDays(WINDOW_DAYS - 1L);
        Map<Long, Integer> shippedQty = shippedQtyInWindow(windowFrom, today);
        Map<Long, LastOrder> lastOrders = lastOrderByProduct();

        return inventoryService.findAllRows().stream()
                .map(row -> toAdvice(row, today, windowFrom,
                        firstRecordedAt.get(row.productId()),
                        shippedQty.getOrDefault(row.productId(), 0),
                        lastOrders.get(row.productId())))
                // nullsLast: 소진 예상이 없는 상품을 위로 올리면 안 붙는 순서가 된다.
                .sorted(Comparator.comparing(ProductAdvice::daysToStockout,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductAdvice::productId))
                .toList();
    }

    private ProductAdvice toAdvice(InventoryRowResponse row, LocalDate today, LocalDate windowFrom,
                                   LocalDate firstRecorded, int shippedQty, LastOrder lastOrder) {
        // 표본 시작 = 창 시작과 그 상품 원장 시작 중 늦은 쪽. 원장이 없으면 표본이 없다.
        long sampleDays = firstRecorded == null ? 0
                : ChronoUnit.DAYS.between(maxOf(windowFrom, firstRecorded), today) + 1;
        double dailyAverage = sampleDays == 0 ? 0 : (double) shippedQty / sampleDays;
        Double daysToStockout = dailyAverage == 0 ? null : row.availableQty() / dailyAverage;
        return new ProductAdvice(row.productId(), row.productName(),
                shippedQty, sampleDays, dailyAverage,
                row.onHandQty(), row.reservedQty(), row.availableQty(),
                daysToStockout, lastOrder);
    }

    private Map<Long, LocalDate> firstRecordedAt() {
        Map<Long, LocalDate> byProduct = new HashMap<>();
        for (Object[] cells : inventoryTransactionRepository.findFirstRecordedAtByProduct())
            byProduct.put((Long) cells[0], ((LocalDateTime) cells[1]).toLocalDate());
        return byProduct;
    }

    /** SHIP 델타는 음수다. 화면에 낼 값은 나간 수량이라 부호를 뒤집는다. */
    private Map<Long, Integer> shippedQtyInWindow(LocalDate from, LocalDate to) {
        Map<Long, Integer> byProduct = new HashMap<>();
        List<Object[]> rows = inventoryTransactionRepository.sumDeltaByProductAndTypeInPeriod(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        for (Object[] cells : rows)
            if (cells[1] == InventoryTransactionType.SHIP)
                byProduct.put((Long) cells[0], -((Number) cells[2]).intValue());
        return byProduct;
    }

    /**
     * 상품별 가장 최근 발주.
     *
     * ponytail: 발주 전건을 메모리에 올려 그룹핑한다(목록 화면이 이미 그렇게 한다).
     * 발주가 수천 건이 되면 상품별 최신 1건 쿼리로 바꾼다.
     */
    private Map<Long, LastOrder> lastOrderByProduct() {
        Map<Long, LastOrder> byProduct = new HashMap<>();
        for (PurchaseOrder po : purchaseOrderService.findAllWithItems()) {
            LocalDate orderedOn = po.getCreatedAt().toLocalDate();
            LocalDate receivedOn = po.getReceivedAt() == null ? null : po.getReceivedAt().toLocalDate();
            Long leadTimeDays = receivedOn == null ? null : ChronoUnit.DAYS.between(orderedOn, receivedOn);
            for (PurchaseOrderItem item : po.getItems()) {
                LastOrder candidate = new LastOrder(po.getId(), po.getStatus(),
                        orderedOn, item.getQuantity(), receivedOn, leadTimeDays);
                byProduct.merge(item.getProductId(), candidate,
                        (kept, incoming) -> incoming.orderedOn().isBefore(kept.orderedOn()) ? kept : incoming);
            }
        }
        return byProduct;
    }

    private static LocalDate maxOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
