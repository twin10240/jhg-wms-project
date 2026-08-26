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
     * <p>먼저 전 품목의 반영 가능 여부를 검증하고, 전부 통과한 뒤에야 반영한다 — 반영을 시작하기
     * 전에 전 품목을 검증하므로, 트랜잭션 롤백에 의존하지 않고도 부분 반영 자체가 애초에 발생하지
     * 않는다. 이 판정은 {@link Inventory#validateDelta}로, onHand를 실제로 변경하는
     * {@link InventoryService#applyDelta}의 가드와 같은 규칙을 공유한다.
     */
    @Transactional
    public void approve(Long sessionId) {
        CycleCount session = findById(sessionId);
        if (session.getStatus() != CycleCountStatus.SUBMITTED)
            throw new IllegalStateException("승인 대기 상태에서만 승인할 수 있습니다.");

        // "센 사람이 스스로 장부를 고치지 못한다" — 제출자와 승인자가 같으면 통제가 없는 것과 같다.
        // 반려는 이 검사를 두지 않는다: 자기 계수를 스스로 물리는 건 막을 이유가 없다.
        String approver = actorProvider.current();
        if (approver.equals(session.getSubmittedBy()))
            throw new IllegalStateException(
                    "제출자는 스스로 승인할 수 없습니다. 다른 담당자가 승인해야 합니다. (제출자=" + session.getSubmittedBy() + ")");

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
            inv.validateDelta(diff);
            diffs.put(item.getProductId(), diff);
        }

        diffs.forEach((productId, diff) -> inventoryService.applyDelta(productId, diff,
                InventoryTransactionType.COUNT, "COUNT#" + sessionId, session.getMemo()));
        session.approve(actorProvider.current());
    }

    /**
     * 실사 중인 상품이면 수동 조정을 거부한다.
     * <p>승인은 계수값을 <b>절대값</b>으로 덮어쓰므로, 세션이 열린 사이에 넣은 조정은 효과가 사라지고
     * 원장에 ADJUST와 COUNT가 상쇄된 채로만 남는다 — 같은 오차를 두 사람이 각자 고치는 상황이다.
     * 조정은 사람이 하는 수동 작업이라 실사가 끝날 때까지 미룰 수 있어 막는다.
     * 출고·입고·반품은 막지 않는다: 미룰 수 없고 OMS 매출까지 멎는다
     * (설계 문서의 "대상 동결 미채택" 판단 그대로).
     * <p>물리 이동 중 계수라는 근본 문제는 이걸로 해결되지 않는다 — 위치 단위 동결이 필요하다.
     */
    public void assertAdjustable(Long productId) {
        if (cycleCountRepository.existsOpenByProductId(productId))
            throw new IllegalStateException(
                    "실사가 진행 중인 상품은 조정할 수 없습니다. 실사를 승인·반려한 뒤 조정하세요. (productId=" + productId + ")");
    }

    @Transactional
    public void reject(Long sessionId, String reason) {
        findById(sessionId).reject(actorProvider.current(), reason);
    }

    public CycleCount findById(Long sessionId) {
        return cycleCountRepository.findById(sessionId)
                .orElseThrow(() -> new CycleCountNotFoundException(sessionId));
    }

    public List<CycleCount> findAll(CycleCountStatus status) {
        return status == null
                ? cycleCountRepository.findAllByOrderByIdDesc()
                : cycleCountRepository.findByStatusOrderByIdDesc(status);
    }

    /** 대시보드 "처리 대기" 집계 — 세션을 전부 로드하지 않고 개수만 센다. */
    public long countPendingApproval() {
        return cycleCountRepository.countByStatus(CycleCountStatus.SUBMITTED);
    }

    /** 이 세션이 실제로 반영한 품목별 차이. 원장이 정본이므로 세션에 복사해두지 않고 여기서 읽는다.
     *  결과에 없는 품목은 차이가 0이었다는 뜻이다(= 일치). */
    public Map<Long, Integer> appliedDeltas(Long sessionId) {
        return transactionRepository.findByReference("COUNT#" + sessionId).stream()
                .collect(Collectors.toMap(InventoryTransaction::getProductId,
                        InventoryTransaction::getDelta, Integer::sum));
    }

    /** 없는 실사 세션. IllegalArgumentException의 하위로 두어, 상태 전이 핸들러들이 이미
     *  업무 오류를 flash로 처리하는 catch(IllegalArgumentException | IllegalStateException) 경로를
     *  그대로 유지한다 — RmaService.RmaNotFoundException과 같은 방식이다. */
    public static class CycleCountNotFoundException extends IllegalArgumentException {
        public CycleCountNotFoundException(Long sessionId) {
            super("실사가 없습니다. id=" + sessionId);
        }
    }
}
