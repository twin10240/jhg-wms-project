package com.jhg.wms.service;

import com.jhg.wms.client.OmsDeliveryNotifier;
import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.config.ActorProvider;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.domain.ReservationStatus;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.web.InventoryRowResponse;
import com.jhg.wms.web.ShipResponse;
import com.jhg.wms.web.ShipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final OmsReplenishmentNotifier omsReplenishmentNotifier;
    private final OmsDeliveryNotifier omsDeliveryNotifier;
    private final ActorProvider actorProvider;

    /** onHand 변경 + 원장 기록의 유일 지점. 모든 실물 변동 경로가 통과한다. */
    @Transactional
    public int applyDelta(Long productId, int delta, InventoryTransactionType type,
                          String reference, String reason) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId));
        int before = inv.getOnHandQty();
        int after = before + delta;
        inv.validateDelta(delta);
        inv.setOnHandQty(after);
        transactionRepository.save(InventoryTransaction.of(productId, type, delta, before, after,
                reference, reason, actorProvider.current()));
        if (delta > 0) {
            // 모든 재고 증가가 통과 — OMS 백오더 승격 트리거(트랜잭션 커밋 후).
            // ponytail: adjust 호출당 HTTP 1발(3품목 입고=3발). 자연 멱등이라 무해 — 배치 필요 시 트랜잭션 스코프 Set으로 모을 것.
            omsReplenishmentNotifier.notifyAfterCommit(productId);
        }
        return after;
    }

    /**
     * 관리자 수동 재고 조정(+/-). 사유 필수.
     * <p>OPERATOR도 조정할 수 있어(SecurityConfig 참고) 통제가 사후 추적뿐이다 —
     * 사유가 비면 원장에 "누가 몇 개"만 남고 "왜"가 비어 추적의 절반이 무너진다.
     * 화면의 required 속성은 직접 POST로 우회되므로 여기가 정본 가드다.
     * applyDelta에는 두지 않는다: RETURN·COUNT는 참조(RMA#/COUNT#)가 사유를 대신한다.
     */
    @Transactional
    public int adjust(Long productId, int delta, String reason) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("조정 사유는 필수입니다.");
        return applyDelta(productId, delta, InventoryTransactionType.ADJUST, null, reason.trim());
    }

    /** 관리자 재고 화면용 전체 목록. */
    public List<InventoryRowResponse> findAllRows() {
        return inventoryRepository.findAll().stream()
                .map(inv -> new InventoryRowResponse(
                        inv.getProductId(), inv.getProductName(),
                        inv.getOnHandQty(), inv.getReservedQty(), inv.getAvailableQty()))
                .sorted(Comparator.comparing(InventoryRowResponse::productId))
                .toList();
    }

    /** 재고 쓰기(reserve/ship/release) 공통 입구 검증. 모든 경로가 통과하는 유일 지점. */
    private static void validateWriteRequest(Long orderId, Map<Long, Integer> qtyByProductId) {
        if (orderId == null)
            throw new IllegalArgumentException("orderId는 필수입니다.");
        if (qtyByProductId == null || qtyByProductId.isEmpty())
            throw new IllegalArgumentException("품목이 없습니다.");
        qtyByProductId.forEach((pid, qty) -> {
            if (pid == null)
                throw new IllegalArgumentException("productId는 null일 수 없습니다.");
            if (qty == null || qty <= 0)
                throw new IllegalArgumentException("수량은 1 이상이어야 합니다. (productId=" + pid + ", qty=" + qty + ")");
        });
    }

    /**
     * 전부-아니면-실패 예약. orderId 멱등: 같은 주문 + 같은 품목 재요청은 현재 상태 그대로 반환.
     * 같은 orderId인데 품목·수량이 다르면 409 — OMS·WMS DB가 따로 초기화돼 orderId가 재사용되면
     * 과거 예약이 현재 주문의 예약으로 오인된다. 상태는 보지 않는다(SHIPPED·RELEASED도 동일하게 거부).
     */
    @Transactional
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        Reservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            Map<Long, Integer> ledger = existing.getQtyByProductId();
            if (!ledger.equals(qtyByProductId))
                throw new IllegalStateException("orderId 예약 원장 불일치 — 같은 orderId의 기존 예약과 요청 품목이 다릅니다."
                        + " orderId=" + orderId + ", 기존원장=" + new TreeMap<>(ledger)
                        + ", 요청=" + new TreeMap<>(qtyByProductId));
            return existing.getStatus() != ReservationStatus.RELEASED;
        }

        Map<Long, Inventory> byId = inventoryRepository.findByProductIdIn(qtyByProductId.keySet())
                .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));

        for (Map.Entry<Long, Integer> e : qtyByProductId.entrySet()) {
            Inventory inv = byId.get(e.getKey());
            if (inv == null || inv.getAvailableQty() < e.getValue()) return false;
        }
        qtyByProductId.forEach((pid, qty) -> byId.get(pid).reserve(qty));
        reservationRepository.save(Reservation.reserve(orderId, qtyByProductId));
        return true;
    }

    /**
     * 예약분 출고 + 송장 발급. 이미 출고됐으면 재고를 다시 깎지 않고, 이미 송장이 있으면 재발급하지 않는다.
     * 해제된 예약은 출고 거부(반쪽 상태 오염 방지).
     * <p>동시 요청은 findByOrderIdWithLock으로 직렬화한다 — 두 번째 요청은 첫 번째가 커밋한 뒤
     * SHIPPED + 송장이 채워진 상태를 보고 같은 값을 반환한다.
     */
    @Transactional
    public ShipResponse shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        // 잠금 조회로 바꾼다 — 두 요청이 동시에 오면 둘 다 SHIPPED 검사를 통과해 송장이 두 장 나온다.
        Reservation reservation = reservationRepository.findByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() == ReservationStatus.RELEASED)
            throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);

        if (reservation.getStatus() != ReservationStatus.SHIPPED) {
            // 호출자 요청 수량이 아니라 예약 원장(SSOT)을 재생한다 — 수량 오염·누락행 침묵 스킵 차단.
            // ship()은 onHand·reserved를 동시에 깎아 applyDelta(onHand 전용)를 못 쓰므로 전용 루프로 SHIP을 기록한다.
            Map<Long, Integer> ledger = reservation.getQtyByProductId();
            Map<Long, Inventory> byId = inventoryRepository.findByProductIdIn(ledger.keySet())
                    .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));
            ledger.forEach((pid, qty) -> {
                Inventory inv = byId.get(pid);
                if (inv == null)
                    throw new IllegalStateException("재고 행이 없어 처리할 수 없습니다. productId=" + pid);
                int before = inv.getOnHandQty();
                inv.ship(qty);
                transactionRepository.save(InventoryTransaction.of(
                    pid, InventoryTransactionType.SHIP, -qty, before, inv.getOnHandQty(),
                    "ORDER#" + orderId, null, actorProvider.current()));
            });
            reservation.ship();
        }
        // 조기 return을 없앤 이유: 이미 출고됐지만 송장이 없는 기존 주문이 여기 도달해야 한다.
        if (reservation.getTrackingNumber() == null)
            reservation.issueShipment(Instant.now());

        return ShipResponse.from(reservation);
    }

    /**
     * 배송 완료 기록 + OMS 통지. 출고되고 송장이 있는 주문만 대상이다.
     * <p>재고는 건드리지 않는다 — 출고에서 이미 차감됐고 배송 완료는 원장 사건이 아니다.
     * <p>재호출은 시각을 덮어쓰지 않고 통지만 재발송한다. 통지가 유실됐을 때(best-effort)
     * 관리자가 화면의 재통지 버튼으로 복구하는 경로이며, OMS 쪽이 멱등이라 안전하다.
     *
     * @return 이번 호출이 배송 완료를 처음 기록했으면 true, 이미 기록돼 있어 통지만 재발송했으면 false
     */
    @Transactional
    public boolean markDelivered(Long orderId) {
        Reservation reservation = reservationRepository.findByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 배송 완료할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() != ReservationStatus.SHIPPED)
            throw new IllegalStateException("출고된 주문만 배송 완료할 수 있습니다. orderId=" + orderId);
        if (reservation.getTrackingNumber() == null)
            throw new IllegalStateException("송장이 없어 배송 완료할 수 없습니다. orderId=" + orderId);

        boolean firstTime = reservation.getDeliveredAt() == null;
        if (firstTime)
            reservation.deliver(Instant.now());
        omsDeliveryNotifier.notifyAfterCommit(orderId, reservation.getDeliveredAt());
        return firstTime;
    }

    /** 예약 해제. 예약이 없거나 이미 해제됐으면 no-op. 출고된 예약은 해제 거부(반쪽 상태 오염 방지). */
    @Transactional
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        // shipAll과 동일하게 잠금 조회를 쓴다 — ship/release 경합이 Inventory의 @Version이 패자를
        // 튕겨내는 우연(현재 flush 순서가 reservation을 inventory보다 먼저 반영하는 것)에 기대지 않고,
        // 락 순서가 명시적으로 결정되도록 한다.
        reservationRepository.findByOrderIdWithLock(orderId).ifPresent(r -> {
            if (r.getStatus() == ReservationStatus.RELEASED) return;
            if (r.getStatus() == ReservationStatus.SHIPPED)
                throw new IllegalStateException("출고된 예약은 해제할 수 없습니다. orderId=" + orderId);
            applyFromLedger(r.getQtyByProductId(), Inventory::release);
            r.release();
        });
    }

    /** 예약 원장의 상품별 수량을 재고에 적용한다. 재고 행이 없으면 침묵 스킵 대신 예외(reserve 가드와 대칭). */
    private void applyFromLedger(Map<Long, Integer> ledger, java.util.function.BiConsumer<Inventory, Integer> op) {
        Map<Long, Inventory> byId = inventoryRepository.findByProductIdIn(ledger.keySet())
                .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));
        ledger.forEach((pid, qty) -> {
            Inventory inv = byId.get(pid);
            if (inv == null)
                throw new IllegalStateException("재고 행이 없어 처리할 수 없습니다. productId=" + pid);
            op.accept(inv, qty);
        });
    }

    /**
     * 송장 조회(읽기 전용). 송장이 발급되지 않은 예약과 없는 예약은 똑같이 비어 있는 결과다.
     * 잠금 없는 findByOrderId를 쓴다 — 아무것도 쓰지 않으므로 다른 요청을 막을 이유가 없다.
     */
    public Optional<ShipmentResponse> findShipment(Long orderId) {
        return reservationRepository.findByOrderId(orderId)
                .filter(r -> r.getTrackingNumber() != null)
                .map(ShipmentResponse::from);
    }

    /** 관리자 예약 화면·대시보드용 전체 예약 목록 (최신 먼저). */
    public List<Reservation> findAllReservations() {
        return reservationRepository.findAllByOrderByIdDesc();   // qtyByProductId 즉시 페치(EntityGraph)
    }

    /** 수불대장: 기간별 상품당 기초·기초설정·입고·반품·출고·조정·실사·기말 집계. */
    public List<LedgerRow> buildLedger(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        Map<Long, Integer> openings = new HashMap<>();
        for (Object[] row : transactionRepository.sumDeltaByProductBefore(fromDt))
            openings.put((Long) row[0], ((Number) row[1]).intValue());

        Map<Long, Map<InventoryTransactionType, Integer>> periodDeltas = new HashMap<>();
        for (Object[] row : transactionRepository.sumDeltaByProductAndTypeInPeriod(fromDt, toDt)) {
            periodDeltas.computeIfAbsent((Long) row[0], k -> new EnumMap<>(InventoryTransactionType.class))
                    .put((InventoryTransactionType) row[1], ((Number) row[2]).intValue());
        }

        Set<Long> allProducts = new TreeSet<>();
        allProducts.addAll(openings.keySet());
        allProducts.addAll(periodDeltas.keySet());
        if (allProducts.isEmpty()) return List.of();

        // 원장에 등장한 상품만 조회 — 전건 로드는 카탈로그가 커질수록 그대로 낭비다.
        Map<Long, String> names = inventoryRepository.findByProductIdIn(allProducts).stream()
                .collect(Collectors.toMap(Inventory::getProductId,
                        inv -> inv.getProductName() != null ? inv.getProductName() : "상품#" + inv.getProductId()));

        List<LedgerRow> rows = new ArrayList<>();
        for (Long pid : allProducts) {
            int opening = openings.getOrDefault(pid, 0);
            Map<InventoryTransactionType, Integer> d = periodDeltas.getOrDefault(pid, Map.of());
            // 기간 내 OPENING(시드·소급)은 수동조정과 성격이 달라 별도 열로 분리한다.
            int initial = d.getOrDefault(InventoryTransactionType.OPENING, 0);
            int receive = d.getOrDefault(InventoryTransactionType.RECEIVE, 0);
            int returnQty = d.getOrDefault(InventoryTransactionType.RETURN, 0);
            int ship = d.getOrDefault(InventoryTransactionType.SHIP, 0);
            int adjust = d.getOrDefault(InventoryTransactionType.ADJUST, 0);
            int countQty = d.getOrDefault(InventoryTransactionType.COUNT, 0);
            rows.add(new LedgerRow(pid, names.getOrDefault(pid, "상품#" + pid), opening, initial,
                    receive, returnQty, ship, adjust, countQty,
                    opening + initial + receive + returnQty + ship + adjust + countQty));
        }
        return rows;
    }

    public record LedgerRow(Long productId, String productName, int opening, int initial,
                             int receive, int returnQty, int ship, int adjust, int countQty, int closing) {}

    /** 원장 합계(기말)와 실제 보유수량이 어긋난 상품. */
    public record InvariantViolation(Long productId, String productName,
                                     int ledgerClosing, int actualOnHand) {}

    /**
     * 원장에서 유도한 기말재고와 실제 onHand를 대조한다.
     * <p>불변식 Σdelta == onHand는 문서 주장이었을 뿐 어디서도 확인하지 않았다.
     * buildLedger가 이미 원장을 집계하므로 대조는 상품 목록 한 번 더 읽는 값이면 된다.
     * <p>주의: 기간의 끝이 오늘 이전이면 기말재고는 그 시점 값이라 현재 onHand와 다른 게 정상이다.
     * 호출 측이 기간을 확인하고 부른다.
     */
    public List<InvariantViolation> findInvariantViolations(List<LedgerRow> ledger) {
        Map<Long, LedgerRow> byId = ledger.stream()
                .collect(Collectors.toMap(LedgerRow::productId, row -> row));
        List<InvariantViolation> violations = new ArrayList<>();
        for (Inventory inv : inventoryRepository.findAll()) {
            LedgerRow row = byId.get(inv.getProductId());
            int closing = row == null ? 0 : row.closing();
            if (inv.getOnHandQty() != closing)
                violations.add(new InvariantViolation(
                        inv.getProductId(), row == null ? inv.getProductName() : row.productName(),
                        closing, inv.getOnHandQty()));
        }
        return violations;
    }

    /** 관리자 화면용 재고 트랜잭션 이력(최신 200건, type 필터 지원). 원장이 계속 자라므로 전건 조회는 하지 않는다. */
    public List<InventoryTransaction> findTransactions(InventoryTransactionType type) {
        return type == null
                ? transactionRepository.findTop200ByOrderByIdDesc()
                : transactionRepository.findTop200ByTypeOrderByIdDesc(type);
    }

    // 범위가 없을 때 날짜에 넣는 경계. PostgreSQL이 null 날짜 파라미터의 타입을 추론하지 못해
    // (:from IS NULL OR ...) 형태가 깨지므로, 조회는 항상 실값 두 개를 받는다.
    // 이 시스템에 있을 수 없는 시각이라 결과를 거르지 않는다.
    private static final LocalDateTime NO_LOWER_BOUND = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime NO_UPPER_BOUND = LocalDateTime.of(9999, 12, 31, 0, 0);

    /** 페이징 이력 조회. 상품·기간은 선택이고, 기간은 buildLedger와 같은 반개구간이다. */
    public org.springframework.data.domain.Page<InventoryTransaction> findTransactions(
            InventoryTransactionType type, Long productId, LocalDate from, LocalDate to,
            org.springframework.data.domain.Pageable pageable) {
        return transactionRepository.search(type, productId,
                from == null ? NO_LOWER_BOUND : from.atStartOfDay(),
                to == null ? NO_UPPER_BOUND : to.plusDays(1).atStartOfDay(),
                pageable);
    }

    /**
     * 한 상품의 수불 행. buildLedger를 그대로 불러 골라낸다 —
     * 계산식을 새로 짜면 수불대장과 대조 줄이 서로 다른 코드가 되어 언젠가 어긋난다.
     */
    public Optional<LedgerRow> ledgerRowOf(Long productId, LocalDate from, LocalDate to) {
        return buildLedger(from, to).stream()
                .filter(row -> row.productId().equals(productId))
                .findFirst();
    }
}
