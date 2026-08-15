package com.jhg.wms.service;

import com.jhg.wms.config.ActorProvider;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public CycleCount findById(Long sessionId) {
        return cycleCountRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("실사가 없습니다. id=" + sessionId));
    }

    public List<CycleCount> findAll(CycleCountStatus status) {
        return status == null
                ? cycleCountRepository.findAllByOrderByIdDesc()
                : cycleCountRepository.findByStatusOrderByIdDesc(status);
    }
}
