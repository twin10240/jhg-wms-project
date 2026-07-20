package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.domain.ReplenishmentRequestStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReplenishmentRequestRepository;
import com.jhg.wms.service.ReplenishmentRequestService.RequestLine;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@Import({ReplenishmentRequestService.class, PurchaseOrderService.class})
class ReplenishmentRequestServiceTest {

    @Autowired ReplenishmentRequestRepository requestRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired ReplenishmentRequestService service;

    @MockitoBean InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryRepository.saveAll(List.of(Inventory.create(1L, 10), Inventory.create(2L, 20)));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void cleanUp() {
        requestRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    void approvesRequestAndCreatesMatchingOrderedPurchaseOrder() {
        var request = service.request(UUID.randomUUID(), "low stock",
                List.of(new RequestLine(1L, 3), new RequestLine(2L, 5))).request();

        Long purchaseOrderId = service.approve(request.getId(), "  ready  ");

        var approved = requestRepository.findById(request.getId()).orElseThrow();
        var purchaseOrder = purchaseOrderRepository.findAllWithItems().get(0);
        assertThat(approved.getStatus()).isEqualTo(ReplenishmentRequestStatus.APPROVED);
        assertThat(approved.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(approved.getDecidedAt()).isNotNull();
        assertThat(approved.getWmsMemo()).isEqualTo("ready");
        assertThat(purchaseOrder.getId()).isEqualTo(purchaseOrderId);
        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(purchaseOrder.getMemo()).isEqualTo("OMS 보충 요청 #" + request.getId() + " - low stock");
        assertThat(purchaseOrder.getItems())
                .extracting(item -> item.getProductId() + ":" + item.getQuantity())
                .containsExactlyInAnyOrder("1:3", "2:5");
    }

    @Test
    void rejectsRequestWithTrimmedMemoAndRejectsBlankMemo() {
        var rejected = service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(1L, 1))).request();
        var blank = service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(2L, 1))).request();

        service.reject(rejected.getId(), "  no capacity  ");

        var saved = requestRepository.findById(rejected.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReplenishmentRequestStatus.REJECTED);
        assertThat(saved.getDecidedAt()).isNotNull();
        assertThat(saved.getWmsMemo()).isEqualTo("no capacity");
        assertThatThrownBy(() -> service.reject(blank.getId(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateApprovalRollsBackAttemptedPurchaseOrder() {
        var request = service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(1L, 1))).request();
        service.approve(request.getId(), null);
        long purchaseOrderCount = purchaseOrderRepository.count();

        assertThatThrownBy(() -> service.approve(request.getId(), "again"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(purchaseOrderRepository.count()).isEqualTo(purchaseOrderCount);
    }

    @Test
    void cannotDecideRejectedRequestOrMissingRequest() {
        var request = service.request(UUID.randomUUID(), "reason", List.of(new RequestLine(1L, 1))).request();
        service.reject(request.getId(), "no");

        assertThatThrownBy(() -> service.approve(request.getId(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(purchaseOrderRepository.count()).isZero();
        assertThatThrownBy(() -> service.reject(request.getId(), "again"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.approve(Long.MAX_VALUE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reject(Long.MAX_VALUE, "no"))
                .isInstanceOf(IllegalArgumentException.class);
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
