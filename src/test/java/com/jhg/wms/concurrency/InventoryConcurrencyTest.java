package com.jhg.wms.concurrency;

import com.jhg.wms.service.InventoryService;
import com.jhg.wms.web.ShipResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("동시 예약이 가용수량을 넘지 못한다 (오버셀 방지)")
class InventoryConcurrencyTest extends ConcurrencySupport {

    @Autowired InventoryService inventoryService;

    @Test
    void 가용_5에_3개씩_두_요청이_동시에_오면_하나만_성공한다() {
        long pid = PID_BASE + 1;
        seedInventory(pid, 5);

        RaceResult result = race(2, i ->
                inventoryService.reserveAll(ORDER_BASE + i, Map.of(pid, 3)));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(reservedOf(pid)).isEqualTo(3);
        assertThat(onHandOf(pid) - reservedOf(pid)).isEqualTo(2);   // 가용 2
    }

    @Test
    void 가용_10에_3개씩_다섯_요청이_동시에_와도_예약은_10을_넘지_않는다() {
        long pid = PID_BASE + 2;
        seedInventory(pid, 10);

        RaceResult result = race(5, i ->
                inventoryService.reserveAll(ORDER_BASE + 10 + i, Map.of(pid, 3)));

        // 몇 건이 성공하느냐는 스케줄링에 따라 흔들린다(실측 1~2건). 그래서 고정 단언 대신
        // 상한(불변 조건)과 하한(최소 성공)을 함께 둔다. 상한만 두면 succeeded()==0에서
        // 모든 단언이 참이 되어 — 0*3 <= 10, reserved == 0, 10-0 >= 0 —
        // reserveAll이 항상 false를 반환하거나 항상 예외를 던져도 이 테스트는 초록이다.
        assertThat(result.succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(result.succeeded() * 3).isLessThanOrEqualTo(10);
        assertThat(reservedOf(pid)).isEqualTo(result.succeeded() * 3);
        assertThat(onHandOf(pid) - reservedOf(pid)).isGreaterThanOrEqualTo(0);

        // 경합이 실제로 있었다는 증거. 진 스레드가 낙관적 락으로 튕겼다는 건 둘 이상이 같은
        // version을 읽고 동시에 쓰려 했다는 뜻이다. 직렬 실행이면 뒤 트랜잭션이 앞이 커밋한
        // version을 읽으므로 이 예외는 원리상 나올 수 없다 — 즉 하니스가 단일 스레드로
        // 퇴화하면 이 단언이 즉시 붉어진다. 이게 없으면 스위트가 조용히 초록으로 남는다.
        // (2스레드 테스트에는 넣지 않는다: 진 쪽이 낙관적 락이 아니라 가용수량 부족으로
        //  false를 반환할 수도 있어 결정적이지 않다.)
        assertThat(result.optimisticLockFailures())
                .as("낙관적 락 충돌이 한 건도 없다 — 실제로 동시에 실행되지 않았다는 뜻")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void 같은_예약을_두_스레드가_동시에_출고해도_이중차감되지_않고_송장은_하나만_발급된다() {
        long pid = PID_BASE + 3;
        long orderId = ORDER_BASE + 20;
        seedInventory(pid, 10);
        inventoryService.reserveAll(orderId, Map.of(pid, 4));

        // 두 스레드가 받은 송장번호를 모은다. 주의: 잠금을 없애도 이 값 자체는 갈라지지 않는다 —
        // 진 스레드는 재고 감소 루프에서 Inventory의 @Version 충돌로 ObjectOptimisticLockingFailureException을
        // 맞고 먼저 튕겨 나가 송장 발급 코드에 도달하지도 못한다(Reservation에는 @Version이 없다).
        // 잠금 해제를 실제로 잡아내는 건 바로 아래 succeeded()==2다 — 이 테스트의 힘은 거기서 나온다.
        Set<String> trackingNumbers = ConcurrentHashMap.newKeySet();
        RaceResult result = race(2, i -> {
            trackingNumbers.add(inventoryService.shipAll(orderId, Map.of(pid, 4)).trackingNumber());
            return true;
        });

        // 출고는 멱등이므로 둘 다 성공해야 한다. 한 쪽이 예외로 튕기면 OMS 재시도가 실패한다.
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(trackingNumbers)
                .as("둘 다 여기 도달했다면(succeeded()==2) 송장은 하나여야 한다 — trackingNumber==null 가드 검증"
                        + "(단, 갈라짐을 막는 잠금 자체는 위 succeeded()==2가 증명한다)")
                .hasSize(1);
        assertThat(onHandOf(pid)).isEqualTo(6);    // 10 - 4, 한 번만 차감
        assertThat(reservedOf(pid)).isZero();
        assertThat(shipRowCountOf(pid))
                .as("SHIP 원장이 두 번 기록됐다 — 재고 차감이 두 번 일어났다는 뜻")
                .isEqualTo(1);
    }

    @Test
    void 이미_출고됐지만_송장이_없는_주문을_두_스레드가_동시에_출고해도_송장은_하나만_발급된다() {
        long pid = PID_BASE + 4;
        long orderId = ORDER_BASE + 30;
        seedInventory(pid, 10);
        inventoryService.reserveAll(orderId, Map.of(pid, 4));
        inventoryService.shipAll(orderId, Map.of(pid, 4));   // SHIPPED + 송장 발급 완료 상태로 만든다

        // 이 기능 이전에 출고된 주문(트래킹 없는 SHIPPED)을 재현한다 — 송장 세 필드를 모두 비운다.
        // 벌크 JPQL UPDATE는 테스트 트랜잭션이 없는 이 클래스에서 tx로 직접 커밋해야 한다.
        tx.executeWithoutResult(s -> em.createQuery(
                        "UPDATE Reservation r SET r.trackingNumber = null, r.carrierCode = null, "
                                + "r.issuedAt = null WHERE r.orderId = :orderId")
                .setParameter("orderId", orderId)
                .executeUpdate());
        em.clear();   // 벌크 UPDATE는 1차 캐시를 건드리지 않는다 — 비우지 않으면 다음 조회가 캐시된 값을 본다.

        // 이 경로는 예약이 이미 SHIPPED라 재고 감소 루프를 건너뛴다 — 두 스레드 다 Inventory를
        // 손대지 않으므로 그 @Version은 애초에 경쟁할 대상이 없다. 즉 위 테스트를 지켜준 낙관적 락은
        // 여기서 무력하고, 남는 방어선은 findByOrderIdWithLock의 Reservation 비관적 락뿐이다.
        Set<String> trackingNumbers = ConcurrentHashMap.newKeySet();
        // trackingNumber만으로는 이 경합을 못 잡는다 — 형식이 초 단위(yyyyMMddHHmmss)라 두 스레드가
        // 같은 초 안에 각자 발급해도 문자열이 우연히 같아진다(실측 확인함). issuedAt(Instant)은 그보다
        // 훨씬 촘촘해 각자 발급이면 사실상 항상 갈라진다 — 그래서 진짜 판별은 이쪽에 싣는다.
        Set<Instant> issuedAts = ConcurrentHashMap.newKeySet();
        RaceResult result = race(2, i -> {
            ShipResponse res = inventoryService.shipAll(orderId, Map.of(pid, 4));
            trackingNumbers.add(res.trackingNumber());
            issuedAts.add(res.issuedAt());
            return true;
        });

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(trackingNumbers)
                .as("잠금이 없으면 둘 다 trackingNumber==null을 보고 각자 새 송장을 발급한다")
                .hasSize(1);
        assertThat(issuedAts)
                .as("잠금이 없으면 각자 자기 시각으로 issueShipment를 호출한다 — 두 스레드가 서로 다른"
                        + " 발급 이벤트를 만들었다는 뜻(잠금이 있으면 뒤 스레드는 이미 채워진 값을 그대로 읽어 반환한다)")
                .hasSize(1);
        assertThat(onHandOf(pid)).isEqualTo(6);   // 최초 출고에서만 차감, 이후 재감소 없음
        assertThat(shipRowCountOf(pid))
                .as("SHIP 원장은 최초 출고 1건뿐이어야 한다 — 이 경로는 재고를 다시 건드리지 않는다")
                .isEqualTo(1);
    }

    /** 상품의 SHIP 원장 행 수. 재고 수치는 맞는데 원장만 두 줄인 상태를 잡는다. */
    private long shipRowCountOf(long productId) {
        return tx.execute(s -> em.createQuery(
                        "SELECT COUNT(t) FROM InventoryTransaction t WHERE t.productId = :pid "
                                + "AND t.type = com.jhg.wms.domain.InventoryTransactionType.SHIP", Long.class)
                .setParameter("pid", productId)
                .getSingleResult());
    }
}
