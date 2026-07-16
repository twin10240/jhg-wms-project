package com.jhg.wms.service;

import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.domain.ReplenishmentRequestItem;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReplenishmentRequestService {

    private final ReplenishmentRequestRepository requestRepository;
    private final InventoryRepository inventoryRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final TransactionTemplate saveTransaction;

    public ReplenishmentRequestService(ReplenishmentRequestRepository requestRepository,
                                       InventoryRepository inventoryRepository,
                                       PurchaseOrderService purchaseOrderService,
                                       PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.inventoryRepository = inventoryRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.saveTransaction = new TransactionTemplate(transactionManager);
        this.saveTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record RequestLine(Long productId, int requestedQty) {}

    public record RequestResult(ReplenishmentRequest request, boolean created) {}

    public RequestResult request(UUID key, String reason, List<RequestLine> lines) {
        if (key == null)
            throw new IllegalArgumentException("requestKey is required");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("reason is required");
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("lines are required");

        String normalizedReason = reason.trim();
        LinkedHashMap<Long, Integer> quantities = new LinkedHashMap<>();
        for (RequestLine line : lines) {
            if (line == null || line.productId() == null)
                throw new IllegalArgumentException("productId is required");
            if (line.requestedQty() < 1)
                throw new IllegalArgumentException("requestedQty must be at least 1");
            if (quantities.putIfAbsent(line.productId(), line.requestedQty()) != null)
                throw new IllegalArgumentException("lines must have unique products");
        }

        var existing = requestRepository.findByRequestKeyWithItems(key);
        if (existing.isPresent())
            return reconcile(existing.get(), normalizedReason, quantities);

        if (inventoryRepository.findByProductIdIn(quantities.keySet()).size() != quantities.size())
            throw new IllegalArgumentException("inventory is missing");

        ReplenishmentRequest request = ReplenishmentRequest.create(key, normalizedReason,
                quantities.entrySet().stream()
                        .map(entry -> ReplenishmentRequestItem.create(entry.getKey(), entry.getValue()))
                        .toArray(ReplenishmentRequestItem[]::new));
        try {
            ReplenishmentRequest saved = saveTransaction.execute(status -> requestRepository.saveAndFlush(request));
            return new RequestResult(saved, true);
        } catch (DataIntegrityViolationException exception) {
            ReplenishmentRequest winner = requestRepository.findByRequestKeyWithItems(key).orElseThrow(() -> exception);
            return reconcile(winner, normalizedReason, quantities);
        }
    }

    public List<ReplenishmentRequest> findAll() {
        return requestRepository.findAllWithItems();
    }

    @Transactional
    public Long approve(Long requestId, String memo) {
        ReplenishmentRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("request is missing: id=" + requestId));
        Long purchaseOrderId = purchaseOrderService.create(
                request.getItems().stream()
                        .map(item -> new PurchaseOrderLine(item.getProductId(), item.getRequestedQty()))
                        .toList(),
                "OMS 보충 요청 #" + request.getId() + " - " + request.getReason());
        request.approve(purchaseOrderId, memo);
        return purchaseOrderId;
    }

    @Transactional
    public void reject(Long requestId, String memo) {
        requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("request is missing: id=" + requestId))
                .reject(memo);
    }

    private RequestResult reconcile(ReplenishmentRequest request, String reason, Map<Long, Integer> quantities) {
        LinkedHashMap<Long, Integer> existingQuantities = new LinkedHashMap<>();
        request.getItems().forEach(item -> existingQuantities.put(item.getProductId(), item.getRequestedQty()));
        if (!request.getReason().equals(reason) || !existingQuantities.equals(quantities))
            throw new IllegalStateException("requestKey already has different content");
        return new RequestResult(request, false);
    }
}
