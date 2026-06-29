package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryTest {

    @Test
    void 가용수량은_보유에서_예약을_뺀_값이다() {
        Inventory inv = Inventory.create(1L, 15);
        inv.setReservedQty(5);
        assertThat(inv.getAvailableQty()).isEqualTo(10);
    }

    @Test
    void 예약이_없으면_가용수량은_보유수량과_같다() {
        Inventory inv = Inventory.create(1L, 15);
        assertThat(inv.getAvailableQty()).isEqualTo(15);
    }
}
