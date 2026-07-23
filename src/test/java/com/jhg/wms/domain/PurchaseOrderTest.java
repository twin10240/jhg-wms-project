package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PurchaseOrderTest {

    /** 영속화 전이라 id가 null이므로 테스트에서 직접 심어 준다. */
    private static PurchaseOrderItem item(long itemId, long productId, int quantity) {
        PurchaseOrderItem item = PurchaseOrderItem.create(productId, quantity);
        ReflectionTestUtils.setField(item, "id", itemId);
        return item;
    }

    private static Map<Long, Integer> qty(long itemId, int quantity) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        map.put(itemId, quantity);
        return map;
    }

    @Test
    void create_발주는_ORDERED_상태로_생성된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급", item(1L, 1L, 10));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급");
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getItems().get(0).getReceivedQty()).isZero();
    }

    @Test
    void receive_전량_입고하면_RECEIVED로_전이한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 5));

        Map<Long, Integer> delta = po.receive(qty(1L, 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 5));
    }

    @Test
    void receive_부분_입고하면_PARTIALLY_RECEIVED가_되고_receivedAt은_null이다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 100));

        Map<Long, Integer> delta = po.receive(qty(1L, 60));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems().get(0).getReceivedQty()).isEqualTo(60);
        assertThat(po.getItems().get(0).remainingQty()).isEqualTo(40);
        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 60));
    }

    @Test
    void receive_잔량을_채우면_RECEIVED가_된다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 100));
        po.receive(qty(1L, 60));

        po.receive(qty(1L, 40));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(po.getItems().get(0).remainingQty()).isZero();
    }

    @Test
    void receive_qty0인_품목은_허용되지만_반환에_담기지_않는다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10), item(2L, 2L, 10));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 4);
        input.put(2L, 0);

        Map<Long, Integer> delta = po.receive(input);

        assertThat(delta).containsExactlyEntriesOf(Map.of(1L, 4));
        assertThat(po.getItems().get(1).getReceivedQty()).isZero();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void receive_같은_상품이_여러_줄이면_반환에서_합산된다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 7L, 10), item(2L, 7L, 5));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 3);
        input.put(2L, 2);

        Map<Long, Integer> delta = po.receive(input);

        assertThat(delta).containsExactlyEntriesOf(Map.of(7L, 5));
    }

    @Test
    void receive_잔량을_초과하면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 3L, 100));
        po.receive(qty(1L, 60));

        assertThatThrownBy(() -> po.receive(qty(1L, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잔량");
    }

    @Test
    void receive_음수는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(qty(1L, -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_전부_0이면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10), item(2L, 2L, 10));
        Map<Long, Integer> input = new LinkedHashMap<>();
        input.put(1L, 0);
        input.put(2L, 0);

        assertThatThrownBy(() -> po.receive(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입고 수량이 없습니다");
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void receive_빈_입력이면_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_발주에_없는_품목ID는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 10));

        assertThatThrownBy(() -> po.receive(qty(999L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void receive_이미_입고완료된_발주는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", item(1L, 1L, 5));
        po.receive(qty(1L, 5));

        assertThatThrownBy(() -> po.receive(qty(1L, 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
