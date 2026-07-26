package com.jhg.wms.service;

import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryService inventoryService;
    private final ReplenishmentRequestRepository requestRepository;

    public record PurchaseOrderLine(Long productId, int quantity) {}

    @Transactional
    public Long create(List<PurchaseOrderLine> lines, String memo) {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("발주 품목이 없습니다.");
        PurchaseOrderItem[] items = lines.stream()
                .map(l -> {
                    if (l.quantity() < 1)
                        throw new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다.");
                    return PurchaseOrderItem.create(l.productId(), l.quantity());
                })
                .toArray(PurchaseOrderItem[]::new);
        return purchaseOrderRepository.save(PurchaseOrder.create(memo, items)).getId();
    }

    @Transactional
    public Long receive(Long poId, Map<Long, Integer> qtyByItemId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));

        // 검증·누적·상태전이는 도메인이 하고, 여기선 실제 반영된 delta만 원장에 넘긴다.
        po.receive(qtyByItemId).forEach((productId, delta) ->
                inventoryService.applyDelta(productId, delta, InventoryTransactionType.RECEIVE,
                        "PO#" + poId, null));

        // 부분 입고 중에 이행 통지를 보내면 "요청 물량을 채웠다"는 거짓 신호가 된다.
        if (po.getStatus() == PurchaseOrderStatus.RECEIVED)
            requestRepository.findByPurchaseOrderId(poId).ifPresent(ReplenishmentRequest::fulfill);

        return po.getId();
    }

    @Transactional
    public void cancel(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
        po.cancel();   // ORDERED·PARTIALLY_RECEIVED만 허용(도메인이 방어)
        requestRepository.findByPurchaseOrderId(poId)
                .ifPresent(ReplenishmentRequest::cancel);   // 연결 요청 종결
    }

    public PurchaseOrder findWithItems(Long poId) {
        return purchaseOrderRepository.findWithItemsById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
    }

    /** 더 진행할 일이 없는 발주(입고완료·취소됨) — 목록에서 뒤로 보낸다. */
    private static boolean isClosed(PurchaseOrder po) {
        return po.getStatus() == PurchaseOrderStatus.RECEIVED
                || po.getStatus() == PurchaseOrderStatus.CANCELLED;
    }

    /**
     * 처리할 발주를 위로: 미완료를 발주일시 오래된 순으로 먼저, 종료된 건(입고완료·취소됨)은 뒤로 보낸다.
     * 정렬을 JPQL이 아니라 여기서 하는 이유 — 조회 쿼리가 {@code select distinct ... join fetch}라
     * PostgreSQL(운영)에서는 "DISTINCT는 ORDER BY 식이 select 목록에 있어야 한다" 제약에 걸린다.
     * 목록은 페이지네이션 없이 전건을 이미 메모리에 올리므로 여기서 정렬해도 추가 비용이 없다.
     * (동일 발주일시는 쿼리의 id desc 순서가 그대로 유지된다 — sorted()가 안정 정렬)
     */
    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems().stream()
                .sorted(Comparator.comparing(PurchaseOrderService::isClosed)
                        .thenComparing(PurchaseOrder::getCreatedAt))
                .toList();
    }
}
