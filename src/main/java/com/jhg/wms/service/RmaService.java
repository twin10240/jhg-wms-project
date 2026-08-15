package com.jhg.wms.service;

import com.jhg.wms.client.OmsReturnStatusNotifier;
import com.jhg.wms.domain.*;
import com.jhg.wms.repository.RmaReturnRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.web.CreateRmaRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RmaService {

    private final RmaReturnRepository rmaReturnRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final OmsReturnStatusNotifier omsReturnStatusNotifier;

    /**
     * RMA 접수. requestKey로 멱등 — 같은 키+같은 내용이면 기존 반환, 다른 내용이면 409.
     * @return 생성 여부(true=신규 201, false=기존 200)와 RmaReturn.
     */
    @Transactional
    public CreateResult createReturn(CreateRmaRequest request) {
        validateCreateRequest(request);

        Reservation reservation = reservationRepository.findByOrderIdWithLock(request.orderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "예약이 없습니다. orderId=" + request.orderId()));

        if (reservation.getStatus() != ReservationStatus.SHIPPED)
            throw new IllegalArgumentException(
                    "출고되지 않은 주문입니다. orderId=" + request.orderId());

        Optional<RmaReturn> existing = rmaReturnRepository.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            if (contentMatches(existing.get(), request))
                return new CreateResult(false, existing.get());
            throw new DuplicateKeyConflictException(request.requestKey());
        }

        Map<Long, Integer> shippedQty = reservation.getQtyByProductId();
        Map<Long, Integer> requestQtyByProduct = aggregateByProductId(request.items());

        for (Long productId : requestQtyByProduct.keySet()) {
            if (!shippedQty.containsKey(productId))
                throw new IllegalArgumentException(
                        "출고 내역에 없는 상품입니다. productId=" + productId);
        }

        Map<Long, Integer> cumulative = cumulativeReturnQty(request.orderId());
        for (var entry : requestQtyByProduct.entrySet()) {
            int total = cumulative.getOrDefault(entry.getKey(), 0) + entry.getValue();
            if (total > shippedQty.get(entry.getKey()))
                throw new IllegalArgumentException(
                        "누적 반품량이 출고량을 초과합니다. productId=" + entry.getKey());
        }

        RmaReturn rma = RmaReturn.create(request.requestKey(), request.orderId(), request.reason());
        for (var item : request.items())
            rma.addItem(item.orderItemId(), item.productId(), item.quantity());

        return new CreateResult(true, rmaReturnRepository.save(rma));
    }

    public RmaReturn findById(Long rmaId) {
        return rmaReturnRepository.findById(rmaId)
                .orElseThrow(() -> new IllegalArgumentException("RMA가 없습니다. rmaId=" + rmaId));
    }

    public List<RmaReturn> findAll(RmaStatus status) {
        return status == null
                ? rmaReturnRepository.findAllByOrderByIdDesc()
                : rmaReturnRepository.findByStatusOrderByIdDesc(status);
    }

    @Transactional
    public void receive(Long rmaId) {
        RmaReturn rma = findById(rmaId);
        rma.receive();
    }

    @Transactional
    public void complete(Long rmaId, Map<Long, InspectionResult> resultsByItemId) {
        RmaReturn rma = findById(rmaId);
        if (rma.getStatus() != RmaStatus.RECEIVED)
            throw new IllegalStateException("RECEIVED 상태에서만 완료할 수 있습니다.");

        for (RmaReturnItem item : rma.getItems()) {
            InspectionResult result = resultsByItemId.get(item.getId());
            if (result == null)
                throw new IllegalArgumentException(
                        "검수 결과가 누락되었습니다. itemId=" + item.getId());
            item.inspect(result.acceptedQuantity(), result.disposition());
        }

        Map<Long, Integer> restockedByProduct = new HashMap<>();
        for (RmaReturnItem item : rma.getItems()) {
            if (item.getDisposition() == RmaDisposition.RESTOCKED && item.getAcceptedQuantity() > 0)
                restockedByProduct.merge(item.getProductId(), item.getAcceptedQuantity(), Integer::sum);
        }
        restockedByProduct.forEach((productId, qty) ->
                inventoryService.applyDelta(productId, qty, InventoryTransactionType.RETURN,
                        "RMA#" + rmaId, null));

        rma.complete();
        omsReturnStatusNotifier.notifyAfterCommit(rma);
    }

    @Transactional
    public void cancel(Long rmaId) {
        RmaReturn rma = findById(rmaId);
        rma.cancel();
        omsReturnStatusNotifier.notifyAfterCommit(rma);
    }

    // ── 내부 ──────────────────────────────────────────────────────

    private void validateCreateRequest(CreateRmaRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank())
            throw new IllegalArgumentException("requestKey는 필수입니다.");
        if (request.orderId() == null)
            throw new IllegalArgumentException("orderId는 필수입니다.");
        if (request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("품목이 없습니다.");
        for (var item : request.items()) {
            if (item.orderItemId() == null)
                throw new IllegalArgumentException("orderItemId는 필수입니다.");
            if (item.productId() == null)
                throw new IllegalArgumentException("productId는 필수입니다.");
            if (item.quantity() <= 0)
                throw new IllegalArgumentException(
                        "수량은 1 이상이어야 합니다. orderItemId=" + item.orderItemId());
        }
    }

    private boolean contentMatches(RmaReturn existing, CreateRmaRequest request) {
        if (!existing.getOrderId().equals(request.orderId())) return false;
        if (!Objects.equals(existing.getReason(), request.reason())) return false;
        if (existing.getItems().size() != request.items().size()) return false;

        Set<String> existingSet = existing.getItems().stream()
                .map(i -> i.getOrderItemId() + "|" + i.getProductId() + "|" + i.getRequestedQuantity())
                .collect(Collectors.toSet());
        Set<String> requestSet = request.items().stream()
                .map(i -> i.orderItemId() + "|" + i.productId() + "|" + i.quantity())
                .collect(Collectors.toSet());
        return existingSet.equals(requestSet);
    }

    private Map<Long, Integer> aggregateByProductId(List<CreateRmaRequest.Item> items) {
        Map<Long, Integer> map = new HashMap<>();
        for (var item : items)
            map.merge(item.productId(), item.quantity(), Integer::sum);
        return map;
    }

    /** CANCELLED 제외, COMPLETED는 acceptedQuantity, 나머지는 requestedQuantity로 합산. */
    private Map<Long, Integer> cumulativeReturnQty(Long orderId) {
        List<RmaReturn> rmas = rmaReturnRepository.findByOrderIdAndStatusNot(orderId, RmaStatus.CANCELLED);
        Map<Long, Integer> cumulative = new HashMap<>();
        for (RmaReturn rma : rmas) {
            for (RmaReturnItem item : rma.getItems()) {
                int qty = (rma.getStatus() == RmaStatus.COMPLETED)
                        ? (item.getAcceptedQuantity() != null ? item.getAcceptedQuantity() : 0)
                        : item.getRequestedQuantity();
                cumulative.merge(item.getProductId(), qty, Integer::sum);
            }
        }
        return cumulative;
    }

    public record CreateResult(boolean created, RmaReturn rma) {}
    public record InspectionResult(int acceptedQuantity, RmaDisposition disposition) {}

    public static class DuplicateKeyConflictException extends RuntimeException {
        public DuplicateKeyConflictException(String requestKey) {
            super("같은 requestKey에 다른 내용의 요청입니다. requestKey=" + requestKey);
        }
    }
}
