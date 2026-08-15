package com.jhg.wms.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ActorProvider actorProvider;

    /** onHand 변경 + 원장 기록의 유일 지점. 모든 실물 변동 경로가 통과한다. */
    @Transactional
    public int applyDelta(Long productId, int delta, InventoryTransactionType type,
                          String reference, String reason) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId));
        int before = inv.getOnHandQty();
        int after = before + delta;
        if (after < 0)
            throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + before + "개)");
        if (after < inv.getReservedQty())
            throw new IllegalArgumentException("예약된 수량(" + inv.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
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

    /** 관리자 수동 재고 조정(+/-). */
    @Transactional
    public int adjust(Long productId, int delta, String reason) {
        return applyDelta(productId, delta, InventoryTransactionType.ADJUST, null, reason);
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

    /** 전부-아니면-실패 예약. orderId 멱등: 같은 주문 재요청은 현재 상태 그대로 반환. */
    @Transactional
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        Reservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) return existing.getStatus() != ReservationStatus.RELEASED;

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

    /** 예약분 출고. 이미 출고됐으면 no-op. 해제된 예약은 출고 거부(반쪽 상태 오염 방지). */
    @Transactional
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() == ReservationStatus.SHIPPED) return;
        if (reservation.getStatus() == ReservationStatus.RELEASED)
            throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);
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

    /** 예약 해제. 예약이 없거나 이미 해제됐으면 no-op. 출고된 예약은 해제 거부(반쪽 상태 오염 방지). */
    @Transactional
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        validateWriteRequest(orderId, qtyByProductId);
        reservationRepository.findByOrderId(orderId).ifPresent(r -> {
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

    /** 관리자 예약 화면·대시보드용 전체 예약 목록 (최신 먼저). */
    public List<Reservation> findAllReservations() {
        return reservationRepository.findAllByOrderByIdDesc();   // qtyByProductId 즉시 페치(EntityGraph)
    }

    /** 수불대장: 기간별 상품당 기초·기초설정·입고·반품·출고·조정·기말 집계. */
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
            rows.add(new LedgerRow(pid, names.getOrDefault(pid, "상품#" + pid), opening, initial,
                    receive, returnQty, ship, adjust,
                    opening + initial + receive + returnQty + ship + adjust));
        }
        return rows;
    }

    public record LedgerRow(Long productId, String productName, int opening, int initial,
                             int receive, int returnQty, int ship, int adjust, int closing) {}

    /** 관리자 화면용 재고 트랜잭션 이력(최신 200건, type 필터 지원). 원장이 계속 자라므로 전건 조회는 하지 않는다. */
    public List<InventoryTransaction> findTransactions(InventoryTransactionType type) {
        return type == null
                ? transactionRepository.findTop200ByOrderByIdDesc()
                : transactionRepository.findTop200ByTypeOrderByIdDesc(type);
    }

    /** 페이징 이력 조회. */
    public org.springframework.data.domain.Page<InventoryTransaction> findTransactions(
            InventoryTransactionType type, org.springframework.data.domain.Pageable pageable) {
        return type == null
                ? transactionRepository.findByOrderByIdDesc(pageable)
                : transactionRepository.findByTypeOrderByIdDesc(type, pageable);
    }
}
