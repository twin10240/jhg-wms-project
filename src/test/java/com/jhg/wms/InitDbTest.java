package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.PurchaseOrderItemRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** InitDb.InitService의 시드/백필 루틴 검증 — 실제 프로덕션 메서드를 직접 호출한다(로직 재구현 금지). */
@DataJpaTest
class InitDbTest {

    @Autowired InventoryRepository inventoryRepository;
    @Autowired InventoryTransactionRepository transactionRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired EntityManager entityManager;
    InitDb.InitService initService;

    @BeforeEach
    void setUp() {
        initService = new InitDb.InitService(inventoryRepository, transactionRepository, purchaseOrderItemRepository);
    }

    @Test
    void seed_상품당_OPENING_트랜잭션을_하나씩_남긴다() {
        initService.seed();

        var openings = transactionRepository.findAllByOrderByIdDesc().stream()
                .filter(t -> t.getType() == InventoryTransactionType.OPENING)
                .toList();
        assertThat(openings).hasSize(20);
        assertThat(openings).allSatisfy(t -> {
            var inv = inventoryRepository.findByProductId(t.getProductId()).orElseThrow();
            assertThat(t.getDelta()).isEqualTo(inv.getOnHandQty());
            assertThat(t.getBeforeQty()).isEqualTo(0);
            assertThat(t.getAfterQty()).isEqualTo(inv.getOnHandQty());
        });
    }

    @Test
    void legacy_백필_후_OPENING이_상품당_하나만_생긴다() {
        inventoryRepository.save(Inventory.create(1L, 30));
        // 기존 조정 이력(type null) 흉내
        transactionRepository.save(InventoryTransaction.of(1L, null, -1, 31, 30, null, "구데이터"));

        initService.migrateLegacy();
        initService.migrateLegacy(); // 재실행해도 결과가 같아야 함(멱등)

        var all = transactionRepository.findAllByOrderByIdDesc();
        assertThat(all).noneMatch(t -> t.getType() == null); // 구데이터가 ADJUST로 채워짐
        assertThat(all.stream().filter(t -> t.getType() == InventoryTransactionType.OPENING)).hasSize(1);

        var opening = all.stream().filter(t -> t.getType() == InventoryTransactionType.OPENING).findFirst().orElseThrow();
        assertThat(opening.getProductId()).isEqualTo(1L);
        assertThat(opening.getBeforeQty()).isEqualTo(0);
        // 원장 잔여분 = 현재 onHand(30) - 기존 델타합(-1) = 31. onHand(30)을 그대로 넣으면 이중계상됨.
        assertThat(opening.getDelta()).isEqualTo(31);
        assertThat(opening.getAfterQty()).isEqualTo(31);

        // Σdelta == onHandQty 불변식 — 기존 배포분(레거시 조정 有) 마이그레이션 경로에서도 성립해야 한다.
        int deltaSum = all.stream().mapToInt(InventoryTransaction::getDelta).sum();
        int onHand = inventoryRepository.findByProductId(1L).orElseThrow().getOnHandQty();
        assertThat(deltaSum).isEqualTo(onHand);
    }

    @Test
    void 발주품목_백필_입고완료는_발주량만큼_대기중은_0으로_채운다() {
        PurchaseOrder ordered = purchaseOrderRepository.save(
                PurchaseOrder.create("대기", PurchaseOrderItem.create(1L, 10)));
        PurchaseOrder received = purchaseOrderRepository.save(
                PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 7)));
        received.receive(Map.of(received.getItems().get(0).getId(), 7));
        purchaseOrderRepository.flush();
        // 기존 배포분 재현: 컬럼이 막 추가된 직후처럼 NULL로 되돌린다.
        entityManager.createNativeQuery("update purchase_order_item set received_qty = null").executeUpdate();
        entityManager.clear();

        initService.migratePurchaseOrderItems();

        assertThat(purchaseOrderRepository.findById(ordered.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isZero();
        assertThat(purchaseOrderRepository.findById(received.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isEqualTo(7);
    }

    @Test
    void 발주품목_백필은_멱등이다() {
        PurchaseOrder po = purchaseOrderRepository.save(
                PurchaseOrder.create("완료", PurchaseOrderItem.create(2L, 7)));
        po.receive(Map.of(po.getItems().get(0).getId(), 3));   // 부분 입고 상태로 저장
        purchaseOrderRepository.flush();
        entityManager.clear();

        initService.migratePurchaseOrderItems();
        initService.migratePurchaseOrderItems();

        // 이미 값이 있으므로 백필이 건드리지 않는다 — 3이 7이나 0으로 덮이지 않는다.
        assertThat(purchaseOrderRepository.findById(po.getId()).orElseThrow()
                .getItems().get(0).getReceivedQty()).isEqualTo(3);
    }
}
