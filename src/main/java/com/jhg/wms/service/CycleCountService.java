package com.jhg.wms.service;

import com.jhg.wms.config.ActorProvider;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountItem;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CycleCountService {

    private final CycleCountRepository cycleCountRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final InventoryService inventoryService;
    private final ActorProvider actorProvider;

    // ponytail: 겹침 검사(findOpenProductIds)와 세션 생성 사이에 락이 없다 — 동시에 두 요청이 들어오면
    // 같은 상품이 두 세션에 함께 담길 수 있다. 세션 개설은 사람이 수동으로 트리거하는 저빈도 작업이고
    // 재고 반영은 승인(approve) 단계에서 그 시점 장부로 재계산되므로, 지금은 데이터가 깨지지 않는다.
    // 실제로 충돌이 발생하면 cycle_count_item(product_id) 부분 유니크 인덱스나 락 기반 단일 쿼리로 올릴 것.
    @Transactional
    public CycleCount open(List<Long> productIds, String memo) {
        if (productIds == null || productIds.isEmpty())
            throw new IllegalArgumentException("실사 대상을 1개 이상 선택해야 합니다.");

        List<Long> distinct = productIds.stream().distinct().toList();
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(distinct).stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i));
        for (Long pid : distinct) {
            if (!inventories.containsKey(pid))
                throw new IllegalArgumentException("재고에 없는 상품입니다. productId=" + pid);
        }

        Set<Long> locked = Set.copyOf(cycleCountRepository.findOpenProductIds());
        List<Long> conflicts = distinct.stream().filter(locked::contains).toList();
        if (!conflicts.isEmpty())
            throw new IllegalStateException("이미 진행 중인 실사에 포함된 상품입니다. productId=" + conflicts);

        CycleCount session = CycleCount.open(actorProvider.current(), memo);
        for (Long pid : distinct)
            session.addItem(pid, inventories.get(pid).getOnHandQty());
        return cycleCountRepository.save(session);
    }

    @Transactional
    public void saveCounts(Long sessionId, Map<Long, Integer> countsByItemId) {
        CycleCount session = findById(sessionId);
        countsByItemId.forEach(session::recordCount);
    }

    @Transactional
    public void submit(Long sessionId) {
        findById(sessionId).submit(actorProvider.current());
    }

    /**
     * 승인. 차이 = 실물 − <b>승인 시점</b> 장부. 세는 동안 재고가 움직여도 원장 불변식이 유지된다.
     * 차이가 있는 품목만 applyDelta를 타므로, 재고가 늘면 OMS 백오더 승격 통지도 그대로 따라온다.
     * <p>먼저 전 품목의 반영 가능 여부를 검증하고, 전부 통과한 뒤에야 반영한다 — 앞 품목을 반영한
     * 뒤 다음 품목에서 실패하는 순서라면(applyDelta의 음수·예약 미만 가드), {@code @Transactional}의
     * 롤백만으로는 앞 품목의 변경이 호출 시점에 이미 보인 상태가 되어 "절반만 반영된 실사"가 관찰될
     * 여지가 남는다. 반영 전에 전량 검증해 애초에 부분 반영이 발생하지 않게 한다.
     */
    @Transactional
    public void approve(Long sessionId) {
        CycleCount session = findById(sessionId);
        if (session.getStatus() != CycleCountStatus.SUBMITTED)
            throw new IllegalStateException("승인 대기 상태에서만 승인할 수 있습니다.");

        Map<Long, Inventory> current = inventoryRepository.findByProductIdIn(
                        session.getItems().stream().map(CycleCountItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Inventory::getProductId, i -> i));

        Map<Long, Integer> diffs = new LinkedHashMap<>();
        for (CycleCountItem item : session.getItems()) {
            Inventory inv = current.get(item.getProductId());
            if (inv == null)
                throw new IllegalArgumentException("재고에 없는 상품입니다. productId=" + item.getProductId());
            int diff = item.getCountedQty() - inv.getOnHandQty();
            if (diff == 0) continue;   // 센 사실은 세션이 기록한다 — delta 0 원장 행은 노이즈다
            int after = inv.getOnHandQty() + diff;
            if (after < 0 || after < inv.getReservedQty())
                throw new IllegalArgumentException(
                        "재고 반영이 불가능한 품목입니다. productId=" + item.getProductId());
            diffs.put(item.getProductId(), diff);
        }

        diffs.forEach((productId, diff) -> inventoryService.applyDelta(productId, diff,
                InventoryTransactionType.COUNT, "COUNT#" + sessionId, session.getMemo()));
        session.approve(actorProvider.current());
    }

    @Transactional
    public void reject(Long sessionId, String reason) {
        findById(sessionId).reject(actorProvider.current(), reason);
    }

    public CycleCount findById(Long sessionId) {
        return cycleCountRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("실사가 없습니다. id=" + sessionId));
    }

    public List<CycleCount> findAll(CycleCountStatus status) {
        return status == null
                ? cycleCountRepository.findAllByOrderByIdDesc()
                : cycleCountRepository.findByStatusOrderByIdDesc(status);
    }

    /** 이 세션이 실제로 반영한 품목별 차이. 원장이 정본이므로 세션에 복사해두지 않고 여기서 읽는다.
     *  결과에 없는 품목은 차이가 0이었다는 뜻이다(= 일치). */
    public Map<Long, Integer> appliedDeltas(Long sessionId) {
        return transactionRepository.findByReference("COUNT#" + sessionId).stream()
                .collect(Collectors.toMap(InventoryTransaction::getProductId,
                        InventoryTransaction::getDelta, Integer::sum));
    }
}
