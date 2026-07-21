package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/** InitDb.InitService의 시드/백필 루틴 검증 — 실제 프로덕션 메서드를 직접 호출한다(로직 재구현 금지). */
@DataJpaTest
class InitDbTest {

    @Autowired InventoryRepository inventoryRepository;
    @Autowired InventoryTransactionRepository transactionRepository;
    InitDb.InitService initService;

    @BeforeEach
    void setUp() {
        initService = new InitDb.InitService(inventoryRepository, transactionRepository);
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
        assertThat(opening.getAfterQty()).isEqualTo(30);
    }
}
