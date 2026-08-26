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

        // 몇 건이 성공하느냐는 스케줄링에 따라 흔들린다. 불변 조건만 단언한다.
        assertThat(result.succeeded() * 3).isLessThanOrEqualTo(10);
        assertThat(reservedOf(pid)).isEqualTo(result.succeeded() * 3);
        assertThat(onHandOf(pid) - reservedOf(pid)).isGreaterThanOrEqualTo(0);
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
