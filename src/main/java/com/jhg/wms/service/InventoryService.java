package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final OmsReplenishmentNotifier omsReplenishmentNotifier;

    /** 관리자 수동 재고 조정(+/-) + 내역 기록. 조정 후 수량을 반환한다. 발주 입고 등 자동 증가는 2-arg를 쓴다(미기록). */
    @Transactional
    public int adjust(Long productId, int delta, String reason) {
        int before = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId))
                .getOnHandQty();
        int after = adjust(productId, delta);
        transactionRepository.save(
                InventoryTransaction.of(productId, InventoryTransactionType.ADJUST, delta, before, after, null, reason));
        return after;
    }

    /** 재고 증가/감소 코어. 내역 미기록 — 수동 경로는 3-arg adjust를 통해 기록한다. */
    @Transactional
    public int adjust(Long productId, int delta) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId));
        int adjusted = inv.getOnHandQty() + delta;
        if (adjusted < 0)
            throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + inv.getOnHandQty() + "개)");
        if (adjusted < inv.getReservedQty())
            throw new IllegalArgumentException("예약된 수량(" + inv.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
        inv.setOnHandQty(adjusted);
        if (delta > 0) {
            // 모든 재고 증가(입고·REST·UI 조정)가 이 지점을 통과한다 — OMS 백오더 승격 트리거.
            // ponytail: adjust 호출당 HTTP 1발(3품목 입고=3발). 자연 멱등이라 무해 — 배치 필요 시 트랜잭션 스코프 Set으로 모을 것.
            omsReplenishmentNotifier.notifyAfterCommit(productId);
        }
        return adjusted;
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
        applyFromLedger(reservation.getQtyByProductId(), Inventory::ship);
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
        return reservationRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    /** 관리자 재고 화면용 수동 조정 내역 (최신 먼저). */
    public List<InventoryTransaction> findAllAdjustments() {
        return transactionRepository.findAllByOrderByIdDesc();
    }
}
