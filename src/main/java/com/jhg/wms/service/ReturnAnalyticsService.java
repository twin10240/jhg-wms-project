package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaReturnItem;
import com.jhg.wms.domain.RmaStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReturnClassificationRepository;
import com.jhg.wms.repository.RmaReturnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 반품 분석 조회. 읽기만 한다 — 재고·반품·분류를 만들지도 고치지도 않는다.
 *
 * LLM을 부르지 않는다. 사유 원문을 읽고 해석하는 일은 MCP 클라이언트의 모델이 한다.
 * 여기에 호출을 심으면 CLI 경로에서 안 돌고 같은 해석을 두 번 만들게 된다.
 */
@Service
@Transactional(readOnly = true)
public class ReturnAnalyticsService {

    /** 원장의 출고 행이 주문을 가리키는 형식. InventoryService가 이 형식으로 쓴다. */
    private static final Pattern ORDER_REF = Pattern.compile("^ORDER#(\\d+)$");

    private final InventoryTransactionRepository transactionRepository;
    private final RmaReturnRepository rmaReturnRepository;
    private final ReturnClassificationRepository classificationRepository;
    private final InventoryRepository inventoryRepository;

    public ReturnAnalyticsService(InventoryTransactionRepository transactionRepository,
                                  RmaReturnRepository rmaReturnRepository,
                                  ReturnClassificationRepository classificationRepository,
                                  InventoryRepository inventoryRepository) {
        this.transactionRepository = transactionRepository;
        this.rmaReturnRepository = rmaReturnRepository;
        this.classificationRepository = classificationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public record ProductReturnRate(Long productId, String productName,
                                    int shippedQty, int returnedQty, double returnRate) {}

    public record ReturnRateReport(LocalDate from, LocalDate to, int observedDays,
                                   List<ProductReturnRate> rows, int unlinkedShipRows) {}

    /**
     * 기간 내 출고 코호트. 분모·분자·원문 조회가 전부 이걸 통해야 정의가 갈라지지 않는다.
     *
     * reference 파싱이 실패한 행은 버리지 않고 센다. 조용히 빠지면 분모만 줄어
     * 반품률이 실제보다 나빠 보인다.
     */
    private record Cohort(Map<Long, Integer> shippedQtyByProduct, Set<Long> orderIds, int unlinkedShipRows) {}

    private Cohort cohort(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        List<InventoryTransaction> ships = transactionRepository.findByTypeInPeriod(
                InventoryTransactionType.SHIP, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        Set<Long> orderIds = new LinkedHashSet<>();
        int unlinked = 0;
        for (InventoryTransaction t : ships) {
            String ref = t.getReference();
            Matcher m = ref == null ? null : ORDER_REF.matcher(ref);
            if (m == null || !m.matches()) {
                unlinked++;
                continue;
            }
            orderIds.add(Long.valueOf(m.group(1)));
            // 출고 delta는 음수다. 수량은 절댓값을 쓴다.
            qtyByProduct.merge(t.getProductId(), Math.abs(t.getDelta()), Integer::sum);
        }
        return new Cohort(qtyByProduct, orderIds, unlinked);
    }

    private List<RmaReturn> cohortReturns(Cohort cohort) {
        // 빈 컬렉션으로 in 절을 만들면 Postgres가 문법 오류를 낸다.
        if (cohort.orderIds().isEmpty()) return List.of();
        return rmaReturnRepository.findByOrderIdInAndStatusNot(cohort.orderIds(), RmaStatus.CANCELLED);
    }

    public ReturnRateReport productReturnRates(LocalDate from, LocalDate to) {
        Cohort cohort = cohort(from, to);

        Map<Long, Integer> returnedByProduct = new HashMap<>();
        for (RmaReturn r : cohortReturns(cohort))
            for (RmaReturnItem i : r.getItems())
                // 분모에 없는 상품은 세지 않는다 — 분모가 없으면 비율이 아니다.
                if (cohort.shippedQtyByProduct().containsKey(i.getProductId()))
                    returnedByProduct.merge(i.getProductId(), i.getRequestedQuantity(), Integer::sum);

        Map<Long, String> names = new HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(cohort.shippedQtyByProduct().keySet()))
            names.put(inv.getProductId(), inv.getProductName());

        List<ProductReturnRate> rows = new ArrayList<>();
        cohort.shippedQtyByProduct().forEach((productId, shipped) -> {
            int returned = returnedByProduct.getOrDefault(productId, 0);
            rows.add(new ProductReturnRate(productId,
                    Objects.requireNonNullElse(names.get(productId), "(이름 없음)"),
                    shipped, returned, shipped == 0 ? 0 : (double) returned / shipped));
        });
        rows.sort(Comparator.comparingDouble(ProductReturnRate::returnRate)
                .thenComparingInt(ProductReturnRate::returnedQty).reversed());

        // 코호트가 아직 성숙하지 않았을 수 있다. 보정하지 않고 경과일을 그대로 낸다.
        long observedDays = Math.max(0, ChronoUnit.DAYS.between(to, LocalDate.now()));
        return new ReturnRateReport(from, to, (int) observedDays, rows, cohort.unlinkedShipRows());
    }
}
