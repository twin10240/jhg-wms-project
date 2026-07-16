package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplenishmentRequestTest {

    @Test
    void create_startsRequested_andLinksItems() {
        ReplenishmentRequestItem item = ReplenishmentRequestItem.create(1L, 3);

        ReplenishmentRequest request = ReplenishmentRequest.create(UUID.randomUUID(), "  low stock  ", item);

        assertThat(request.getStatus()).isEqualTo(ReplenishmentRequestStatus.REQUESTED);
        assertThat(request.getReason()).isEqualTo("low stock");
        assertThat(request.getRequestedAt()).isNotNull();
        assertThat(request.getItems()).containsExactly(item);
        assertThat(item.getRequest()).isSameAs(request);
    }

    @Test
    void create_rejectsDuplicateProducts() {
        assertThatThrownBy(() -> ReplenishmentRequest.create(UUID.randomUUID(), "reason",
                ReplenishmentRequestItem.create(1L, 1),
                ReplenishmentRequestItem.create(1L, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_thenFulfill() {
        ReplenishmentRequest request = request();

        request.approve(10L, "  ordered  ");

        assertThat(request.getStatus()).isEqualTo(ReplenishmentRequestStatus.APPROVED);
        assertThat(request.getPurchaseOrderId()).isEqualTo(10L);
        assertThat(request.getWmsMemo()).isEqualTo("ordered");
        assertThat(request.getDecidedAt()).isNotNull();

        request.fulfill();

        assertThat(request.getStatus()).isEqualTo(ReplenishmentRequestStatus.FULFILLED);
        assertThat(request.getFulfilledAt()).isNotNull();
    }

    @Test
    void reject() {
        ReplenishmentRequest request = request();

        request.reject("  unnecessary  ");

        assertThat(request.getStatus()).isEqualTo(ReplenishmentRequestStatus.REJECTED);
        assertThat(request.getWmsMemo()).isEqualTo("unnecessary");
        assertThat(request.getDecidedAt()).isNotNull();
    }

    @Test
    void reject_requiresMemo() {
        assertThatThrownBy(() -> request().reject(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().reject("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidTransitions() {
        ReplenishmentRequest approved = request();
        approved.approve(10L, null);
        ReplenishmentRequest rejected = request();
        rejected.reject("not needed");

        assertThatThrownBy(() -> approved.approve(11L, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.reject(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(rejected::fulfill).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesRequiredValuesAndQuantity() {
        ReplenishmentRequestItem item = ReplenishmentRequestItem.create(1L, 1);

        assertThatThrownBy(() -> ReplenishmentRequest.create(null, "reason", item))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplenishmentRequest.create(UUID.randomUUID(), "  ", item))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplenishmentRequest.create(UUID.randomUUID(), "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplenishmentRequestItem.create(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplenishmentRequestItem.create(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().approve(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReplenishmentRequest request() {
        return ReplenishmentRequest.create(UUID.randomUUID(), "reason",
                ReplenishmentRequestItem.create(1L, 1));
    }
}
