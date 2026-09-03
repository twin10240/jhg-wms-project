package com.jhg.wms.service;

import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountItem;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실사 분석 조회. 읽기만 한다 — 실사·재고·원장을 만들지도 고치지도 않는다.
 *
 * <p>LLM을 부르지 않는다. 숫자를 읽고 무엇을 쓸지 정하는 일은 MCP 클라이언트의 모델이 한다
 * ({@code ReturnAnalyticsService}와 같은 규칙이다).
 *
 * <p><b>구간 판정은 {@code createdAt}(실사를 시작한 시각)이다.</b> 승인 시각이 아니다 —
 * 반려·진행 중 세션은 승인 시각이 없어서, 그걸 기준으로 삼으면 상태 분포를 낼 수 없다.
 * 응답의 {@code basis}가 이 사실을 싣는다.
 */
@Service
@Transactional(readOnly = true)
public class CycleCountAnalyticsService {

    private final CycleCountRepository cycleCountRepository;
    private final InventoryRepository inventoryRepository;

    public CycleCountAnalyticsService(CycleCountRepository cycleCountRepository,
                                      InventoryRepository inventoryRepository) {
        this.cycleCountRepository = cycleCountRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public record SessionCounts(int open, int submitted, int approved, int rejected, int total) {}

    /**
     * @param accuracy 잴 것이 없으면 <b>null</b>이다. 0.0으로 내면 "전부 틀렸다"로 읽힌다 —
     *                 잴 것이 없는 것과 다 틀린 것은 다르다.
     * @param excludedRejectedItems 반려 세션의 항목 수. 정확도 분모에서 뺐다는 사실을 밝히는 근거다.
     */
    public record AccuracyReport(LocalDate from, LocalDate to, String basis,
                                 SessionCounts sessions,
                                 int countedItems, int matchedItems, Double accuracy,
                                 int overItems, int overQty,
                                 int underItems, int underQty,
                                 int excludedRejectedItems) {}

    public record VarianceRow(Long cycleCountId, int bookQty, int countedQty, int diff,
                              LocalDateTime countedAt) {}

    public record ProductVariance(Long productId, String productName,
                                  int occurrences, int netQty, List<VarianceRow> rows) {}

    /**
     * 계수 정확도. <b>승인된 세션의 항목만</b> 분모에 넣는다.
     *
     * <p>반려는 "계수를 신뢰할 수 없다"고 사람이 판정한 것이다. 그 항목을 정확도에 넣으면
     * 창고의 계수 능력이 아니라 반려 사유를 재게 된다. 대신 뺀 개수를
     * {@code excludedRejectedItems}로 함께 낸다 — 조용히 빼면 분모를 속이는 것과 같다.
     */
    public AccuracyReport accuracy(LocalDate from, LocalDate to) {
        List<CycleCount> sessions = sessionsIn(from, to);

        int open = 0, submitted = 0, approved = 0, rejected = 0;
        int countedItems = 0, matchedItems = 0;
        int overItems = 0, overQty = 0, underItems = 0, underQty = 0, excludedRejectedItems = 0;

        for (CycleCount session : sessions) {
            switch (session.getStatus()) {
                case OPEN -> open++;
                case SUBMITTED -> submitted++;
                case APPROVED -> approved++;
                case REJECTED -> rejected++;
            }
            if (session.getStatus() == CycleCountStatus.REJECTED) {
                excludedRejectedItems += session.getItems().size();
                continue;
            }
            if (session.getStatus() != CycleCountStatus.APPROVED) continue;

            for (CycleCountItem item : session.getItems()) {
                Integer counted = item.getCountedQty();
                if (counted == null) continue;   // 승인된 세션에는 없지만, 있어도 세지 않는다
                countedItems++;
                int diff = counted - item.getBookQtyAtOpen();
                if (diff == 0) { matchedItems++; }
                else if (diff > 0) { overItems++; overQty += diff; }
                else { underItems++; underQty += -diff; }
            }
        }

        Double accuracy = countedItems == 0 ? null : (double) matchedItems / countedItems;
        return new AccuracyReport(from, to, "createdAt",
                new SessionCounts(open, submitted, approved, rejected, sessions.size()),
                countedItems, matchedItems, accuracy,
                overItems, overQty, underItems, underQty, excludedRejectedItems);
    }

    /**
     * 차이가 난 상품 목록. 승인된 세션만 본다(정확도와 같은 모수).
     *
     * <p>정렬은 <b>반복 횟수가 먼저</b>고 그다음이 순차이의 크기다. 한 번 크게 틀린 것보다
     * 여러 번 조금씩 틀리는 쪽이 로케이션·라벨 같은 구조적 원인을 가리키기 때문이다.
     */
    public List<ProductVariance> variances(LocalDate from, LocalDate to) {
        Map<Long, List<VarianceRow>> rowsByProduct = new LinkedHashMap<>();

        for (CycleCount session : sessionsIn(from, to)) {
            if (session.getStatus() != CycleCountStatus.APPROVED) continue;
            for (CycleCountItem item : session.getItems()) {
                Integer counted = item.getCountedQty();
                if (counted == null) continue;
                int diff = counted - item.getBookQtyAtOpen();
                if (diff == 0) continue;
                rowsByProduct.computeIfAbsent(item.getProductId(), k -> new ArrayList<>())
                        .add(new VarianceRow(session.getId(), item.getBookQtyAtOpen(), counted, diff,
                                session.getSubmittedAt()));
            }
        }
        if (rowsByProduct.isEmpty()) return List.of();

        // Collectors.toMap은 값이 null이면 NPE다. 이름은 나중에 도입된 컬럼이라 null일 수 있고
        // (InitDb.backfillNames가 그 잔재를 메운다), 이름 없는 상품 하나가 보고서 전체를 막으면 안 된다.
        Map<Long, String> nameByProduct = new java.util.HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(rowsByProduct.keySet()))
            nameByProduct.put(inv.getProductId(), inv.getProductName());

        List<ProductVariance> result = new ArrayList<>();
        rowsByProduct.forEach((productId, rows) -> {
            int net = rows.stream().mapToInt(VarianceRow::diff).sum();
            result.add(new ProductVariance(productId, nameByProduct.get(productId),
                    rows.size(), net, List.copyOf(rows)));
        });

        result.sort(Comparator.comparingInt(ProductVariance::occurrences).reversed()
                .thenComparing(Comparator.comparingInt((ProductVariance v) -> Math.abs(v.netQty())).reversed())
                .thenComparing(ProductVariance::productId));
        return result;
    }

    /** to는 그날 하루를 포함한다 — 화면·보고서가 "9/1~9/3"을 3일로 읽는 것과 맞춘다. */
    private List<CycleCount> sessionsIn(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        return cycleCountRepository.findCreatedBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }
}
