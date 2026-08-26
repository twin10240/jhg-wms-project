package com.jhg.wms.concurrency;

import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

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
    void 같은_예약을_두_스레드가_동시에_출고해도_이중_차감되지_않는다() {
        long pid = PID_BASE + 3;
        long orderId = ORDER_BASE + 20;
        seedInventory(pid, 10);
        inventoryService.reserveAll(orderId, Map.of(pid, 4));

        race(2, i -> {
            inventoryService.shipAll(orderId, Map.of(pid, 4));
            return true;
        });

        assertThat(onHandOf(pid)).isEqualTo(6);    // 10 - 4, 한 번만 차감
        assertThat(reservedOf(pid)).isZero();
    }
}
