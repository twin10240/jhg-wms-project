package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PurchaseOrderTest {

    @Test
    void create_발주는_ORDERED_상태로_생성된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급");
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getCreatedAt()).isNotNull();
    }

    @Test
    void receive_ORDERED에서_RECEIVED로_전이한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void receive_이미_입고된_발주는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
