package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void reserve_가용수량이_충분하면_예약하고_true를_반환한다() {
        Inventory inv = Inventory.create(1L, 10);
        assertThat(inv.reserve(7)).isTrue();
        assertThat(inv.getReservedQty()).isEqualTo(7);
    }

    @Test
    void reserve_가용수량이_부족하면_예약하지_않고_false를_반환한다() {
        Inventory inv = Inventory.create(1L, 5);
        assertThat(inv.reserve(6)).isFalse();
        assertThat(inv.getReservedQty()).isEqualTo(0);
    }

    @Test
    void ship_출고하면_보유와_예약이_모두_줄어든다() {
        Inventory inv = Inventory.create(1L, 10);
        inv.reserve(6);
        inv.ship(6);
        assertThat(inv.getOnHandQty()).isEqualTo(4);
        assertThat(inv.getReservedQty()).isEqualTo(0);
    }

    @Test
    void release_해제하면_예약분이_줄어든다() {
        Inventory inv = Inventory.create(1L, 10);
        inv.reserve(6);
        inv.release(6);
        assertThat(inv.getReservedQty()).isEqualTo(0);
        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }

    // 다품목 실사 승인처럼 여러 상품을 한꺼번에 검증할 때, 메시지에 productId가 없으면
    // 어느 상품이 걸렸는지 알 수 없다.
    @Test
    void validateDelta_0미만_거부_메시지에_productId가_있다() {
        Inventory inv = Inventory.create(42L, 3);
        assertThatThrownBy(() -> inv.validateDelta(-4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId=42");
    }

    @Test
    void validateDelta_예약수량_미만_거부_메시지에_productId가_있다() {
        Inventory inv = Inventory.create(42L, 10);
        inv.setReservedQty(8);
        assertThatThrownBy(() -> inv.validateDelta(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId=42");
    }
}
