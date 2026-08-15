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
