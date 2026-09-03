package com.jhg.wms.service;

import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 추적 조회. 읽기만 한다.
 *
 * <p>LLM을 부르지 않는다 — 숫자를 읽고 무엇을 쓸지 정하는 일은 MCP 클라이언트의 모델이 한다
 * ({@code CycleCountAnalyticsService}와 같은 규칙이다).
 *
 * <p><b>{@code actor}를 내보내지 않는다.</b> 원장 행에는 있지만 이 보고서에는 담지 않는다.
 * 모델이 사람을 지목할 수 있게 되면 근거가 "같은 계정이 두 번 나왔다" 수준이어도 보고서는
 * 지목한 문장으로 나간다. 행위자 확인은 원장 화면(V7)이 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class InventoryLedgerAnalyticsService {

    /** 응답 행 상한. 넘으면 최근 것부터 남긴다 — 조사 중인 사건은 최근에 있다. */
    static final int MAX_ROWS = 500;

    private final InventoryTransactionRepository inventoryTransactionRepository;

    public InventoryLedgerAnalyticsService(InventoryTransactionRepository inventoryTransactionRepository) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
    }

    /** @param occurredAt 원장에 찍힌 시각. 행위자는 담지 않는다. */
    public record LedgerRow(InventoryTransactionType type, int delta, int beforeQty, int afterQty,
                            String reference, String reason, LocalDateTime occurredAt) {}

    /**
     * @param truncated 상한에서 잘렸는지. <b>잘랐으면 반드시 true여야 한다</b> — 조용히 자르면
     *                  모델이 받은 것을 전량으로 읽고 "그 사이 이동이 없었다"고 쓴다.
     * @param total 자르기 전 전체 행 수.
     */
    public record LedgerReport(Long productId, LocalDate from, LocalDate to,
                               List<LedgerRow> rows, boolean truncated, long total) {}

    /**
     * 한 상품의 기간 내 원장. <b>시간 오름차순</b>이다 — 추적은 흐름으로 읽는다.
     *
     * <p>화면은 최신순이지만(`id DESC`) 여기서는 뒤집는다. beforeQty→afterQty가 행마다
     * 이어붙어야 사슬이 끊긴 자리가 보이고, 그 불연속이 기록되지 않은 이동의 흔적이다.
     */
    public LedgerReport ledger(Long productId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 구간이 뒤집혔습니다: " + from + " ~ " + to);
        }
        LocalDateTime fromAt = from.atStartOfDay();
        LocalDateTime toAt = to.plusDays(1).atStartOfDay();   // 종료일 당일을 포함한다

        // search는 id DESC로 준다. 첫 페이지 = 최근 MAX_ROWS행.
        Page<InventoryTransaction> page = inventoryTransactionRepository.search(
                null, productId, fromAt, toAt, PageRequest.of(0, MAX_ROWS));

        List<LedgerRow> rows = new ArrayList<>(page.getContent().stream().map(
                t -> new LedgerRow(t.getType(), t.getDelta(), t.getBeforeQty(), t.getAfterQty(),
                                   t.getReference(), t.getReason(), t.getCreatedAt())).toList());
        rows.sort(java.util.Comparator.comparing(LedgerRow::occurredAt));   // 시간 오름차순

        return new LedgerReport(productId, from, to, rows,
                                page.getTotalElements() > MAX_ROWS, page.getTotalElements());
    }
}
