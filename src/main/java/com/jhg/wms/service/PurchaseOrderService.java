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

    public PurchaseOrder findWithItems(Long poId) {
        return purchaseOrderRepository.findWithItemsById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
