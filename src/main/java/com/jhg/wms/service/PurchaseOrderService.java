package com.jhg.wms.service;

import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryService inventoryService;

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
    public Long receive(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
        po.receive(); // 중복 입고 시 IllegalStateException
        po.getItems().forEach(item -> inventoryService.adjust(item.getProductId(), item.getQuantity()));
        // ponytail: S3에서 OMS 콜백(StockReplenishedHandler) 추가. 현재는 OMS가 자체 트리거 유지.
        return po.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
