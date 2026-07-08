package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.domain.ReservationStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.web.InventoryRowResponse;
import lombok.RequiredArgsConstructor;
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
    private final OmsReplenishmentNotifier omsReplenishmentNotifier;

    /** 관리자 수동 재고 조정(+/-). 조정 후 수량을 반환한다. */
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
                .map(inv -> new InventoryRowResponse(inv.getProductId(), inv.getOnHandQty()))
                .sorted(Comparator.comparing(InventoryRowResponse::productId))
                .toList();
    }

    /** 전부-아니면-실패 예약. orderId 멱등: 같은 주문 재요청은 현재 상태 그대로 반환. */
    @Transactional
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) return existing.getStatus() != ReservationStatus.RELEASED;

        Map<Long, Inventory> byId = inventoryRepository.findByProductIdIn(qtyByProductId.keySet())
                .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));

        for (Map.Entry<Long, Integer> e : qtyByProductId.entrySet()) {
            Inventory inv = byId.get(e.getKey());
            if (inv == null || inv.getAvailableQty() < e.getValue()) return false;
        }
        qtyByProductId.forEach((pid, qty) -> byId.get(pid).reserve(qty));
        reservationRepository.save(Reservation.reserve(orderId));
        return true;
    }

    /** 예약분 출고. 이미 출고됐으면 no-op. 해제된 예약은 출고 거부(반쪽 상태 오염 방지). */
    @Transactional
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() == ReservationStatus.SHIPPED) return;
        if (reservation.getStatus() == ReservationStatus.RELEASED)
            throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);
        inventoryRepository.findByProductIdIn(qtyByProductId.keySet())
                .forEach(inv -> inv.ship(qtyByProductId.get(inv.getProductId())));
        reservation.ship();
    }

    /** 예약 해제. 예약이 없거나 이미 해제됐으면 no-op. */
    @Transactional
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        reservationRepository.findByOrderId(orderId).ifPresent(r -> {
            if (r.getStatus() == ReservationStatus.RELEASED) return;
            inventoryRepository.findByProductIdIn(qtyByProductId.keySet())
                    .forEach(inv -> inv.release(qtyByProductId.get(inv.getProductId())));
            r.release();
        });
    }
}
