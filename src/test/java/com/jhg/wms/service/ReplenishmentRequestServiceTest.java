package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.service.ReplenishmentRequestService.RequestLine;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReplenishmentRequestServiceTest {

    @Autowired ReplenishmentRequestRepository requestRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    ReplenishmentRequestService service;

    @BeforeEach
    void setUp() {
        service = new ReplenishmentRequestService(requestRepository, inventoryRepository, transactionManager);
        inventoryRepository.saveAll(List.of(Inventory.create(1L, 10), Inventory.create(2L, 20)));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void cleanUp() {
        requestRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    void sameKeyAndContentConvergesOnOneRequest() {
        UUID key = UUID.randomUUID();

        var first = service.request(key, "  low stock  ", List.of(new RequestLine(1L, 3)));
        var second = service.request(key, "low stock", List.of(new RequestLine(1L, 3)));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.request().getId()).isEqualTo(first.request().getId());
        assertThat(requestRepository.count()).isOne();
    }

    @Test
    void retryReconcilesExistingRequestWhenInventoryWasRemoved() {
        UUID key = UUID.randomUUID();
        var first = service.request(key, "low stock", List.of(new RequestLine(1L, 3)));
        inventoryRepository.deleteAll();

        var retry = service.request(key, "low stock", List.of(new RequestLine(1L, 3)));

        assertThat(retry.created()).isFalse();
        assertThat(retry.request().getId()).isEqualTo(first.request().getId());
    }

    @Test
    void sameKeyRejectsDifferentReasonOrItems() {
        UUID reasonKey = UUID.randomUUID();
        service.request(reasonKey, "low stock", List.of(new RequestLine(1L, 3)));

        assertThatThrownBy(() -> service.request(reasonKey, "different", List.of(new RequestLine(1L, 3))))
                .isInstanceOf(IllegalStateException.class);

        UUID itemsKey = UUID.randomUUID();
        service.request(itemsKey, "low stock", List.of(new RequestLine(1L, 3)));

        assertThatThrownBy(() -> service.request(itemsKey, "low stock", List.of(new RequestLine(1L, 4))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lineOrderDoesNotAffectIdempotency() {
        UUID key = UUID.randomUUID();
        var first = service.request(key, "reason", List.of(new RequestLine(1L, 2), new RequestLine(2L, 4)));

        var second = service.request(key, "reason", List.of(new RequestLine(2L, 4), new RequestLine(1L, 2)));

        assertThat(second.created()).isFalse();
        assertThat(second.request().getId()).isEqualTo(first.request().getId());
    }

    @Test
    void rejectsMissingInventory() {
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason",
                List.of(new RequestLine(1L, 1), new RequestLine(999L, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingReasonOrLines() {
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), null, List.of(new RequestLine(1L, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "  ", List.of(new RequestLine(1L, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidOrDuplicateLines() {
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(null, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(1L, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(UUID.randomUUID(), "reason",
                List.of(new RequestLine(1L, 1), new RequestLine(1L, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchQueriesInitializeItemsAfterFlushAndClear() {
        UUID key = UUID.randomUUID();
        service.request(key, "reason", List.of(new RequestLine(1L, 1)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        ReplenishmentRequest byKey = transaction.execute(status -> {
            entityManager.flush();
            entityManager.clear();
            return requestRepository.findByRequestKeyWithItems(key).orElseThrow();
        });
        ReplenishmentRequest fromAll = transaction.execute(status -> {
            entityManager.flush();
            entityManager.clear();
            return requestRepository.findAllWithItems().get(0);
        });

        assertThat(Hibernate.isInitialized(byKey.getItems())).isTrue();
        assertThat(Hibernate.isInitialized(fromAll.getItems())).isTrue();
        assertThat(service.findAll()).hasSize(1);
        assertThat(Hibernate.isInitialized(service.findAll().get(0).getItems())).isTrue();
    }
}
