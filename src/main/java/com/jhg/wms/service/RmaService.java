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
    private final ReturnClassificationTrigger returnClassificationTrigger;

    /**
     * RMA 접수. requestKey로 멱등 — 같은 키+같은 내용이면 기존 반환, 다른 내용이면 409.
     * @return 생성 여부(true=신규 201, false=기존 200)와 RmaReturn.
     */
    @Transactional
    public CreateResult createReturn(CreateRmaRequest request) {
        validateCreateRequest(request);

        Reservation reservation = findReservation(request);

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

        // ponytail: 누적은 아직 orderId로 센다. 재사용된 orderId에서는 옛 주문의 반품까지 세어
        // 과다 집계될 수 있는데, 과다 집계는 접수를 '거절'하는 방향이라 조용한 오답이 아니다
        // (예약 선택이 틀리는 것과 달리 사람이 바로 안다). 모든 반품이 orderRequestKey를
        // 싣게 되면 RmaReturn에 그 키를 저장하고 여기도 키 기준으로 바꾼다.
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

        RmaReturn saved = rmaReturnRepository.save(rma);
        // 분류는 참고 정보라 접수와 한 트랜잭션에 묶지 않는다 —
        // 외부 LLM 장애가 반품 접수를 막으면 안 된다.
        returnClassificationTrigger.classifyAfterCommit(saved.getId(), saved.getReason());
        return new CreateResult(true, saved);
    }

    public RmaReturn findById(Long rmaId) {
        return rmaReturnRepository.findById(rmaId)
                .orElseThrow(() -> new RmaNotFoundException(rmaId));
    }

    public List<RmaReturn> findAll(RmaStatus status) {
        return status == null
                ? rmaReturnRepository.findAllByOrderByIdDesc()
                : rmaReturnRepository.findByStatusOrderByIdDesc(status);
    }

    /**
     * 입고 처리. OMS 고객 화면이 "창고 도착"을 보여주므로 결과뿐 아니라 이 전이도 통지한다.
     * 통지가 없으면 OMS는 60초 보상 스윕으로만 RECEIVED를 발견하는데, 그 안에 검수가 끝나면
     * 고객은 접수에서 완료로 건너뛰는 화면을 보게 된다.
     * 통지는 커밋 후 best-effort — 실패해도 입고를 되돌리지 않고 스윕이 회수한다.
     */
    @Transactional
    public void receive(Long rmaId) {
        RmaReturn rma = findById(rmaId);
        rma.receive();
        omsReturnStatusNotifier.notifyAfterCommit(rma);
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

    /**
     * 반품 대상 예약을 찾는다.
     *
     * <p>{@code orderRequestKey}가 오면 <b>단건 조회</b>다. orderId는 유일하지 않아서
     * (OMS DB 초기화로 재사용된다) "가장 최근 예약" 추측은 옛 주문의 반품을 새 주문에 붙이거나,
     * 실제로 출고된 상품을 "출고 내역에 없다"고 거절한다 — 예약/출고 경로가 PR #23에서
     * requestKey로 옮겨간 것과 같은 이유다.
     *
     * <p>키가 없으면 레거시 경로다. OMS가 모든 반품 요청에 키를 싣게 되면 이 분기와
     * {@code findByOrderIdLatestFirstWithLock}을 같이 지운다.
     */
    private Reservation findReservation(CreateRmaRequest request) {
        if (!hasOrderRequestKey(request))
            return reservationRepository.findByOrderIdLatestFirstWithLock(request.orderId())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "예약이 없습니다. orderId=" + request.orderId()));

        UUID key = UUID.fromString(request.orderRequestKey());   // 형식은 validate에서 이미 걸렀다
        Reservation reservation = reservationRepository.findByRequestKeyWithLock(key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "예약이 없습니다. orderRequestKey=" + key));

        // 둘 다 OMS가 같은 요청에 실어 보낸 값이다. 어긋나면 보내는 쪽이 헷갈린 것이므로
        // 한쪽을 임의로 믿지 않는다 — 잘못 믿으면 남의 주문에 반품이 붙는다.
        if (!reservation.getOrderId().equals(request.orderId()))
            throw new IllegalArgumentException(
                    "orderRequestKey가 orderId와 맞지 않습니다. orderId=" + request.orderId());
        return reservation;
    }

    private static boolean hasOrderRequestKey(CreateRmaRequest request) {
        return request.orderRequestKey() != null && !request.orderRequestKey().isBlank();
    }

    private void validateCreateRequest(CreateRmaRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank())
            throw new IllegalArgumentException("requestKey는 필수입니다.");
        if (request.orderId() == null)
            throw new IllegalArgumentException("orderId는 필수입니다.");
        if (request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("품목이 없습니다.");
        // 형식은 경계에서 막는다. 안쪽에서 UUID.fromString이 터지면 400이어야 할 것이 500이 된다.
        if (hasOrderRequestKey(request)) {
            try {
                UUID.fromString(request.orderRequestKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("orderRequestKey는 UUID 형식이어야 합니다.");
            }
        }
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

    /** 없는 RMA. OMS 보상 스윕이 404를 "이 rmaId는 없다"로 해석하므로 400과 구분해야 한다.
     *  IllegalArgumentException의 하위로 두어, 이미 업무 오류를 flash로 처리하는 관리자 화면의
     *  catch 경로를 그대로 유지한다. */
    public static class RmaNotFoundException extends IllegalArgumentException {
        public RmaNotFoundException(Long rmaId) {
            super("RMA가 없습니다. rmaId=" + rmaId);
        }
    }
}
