package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL에 부팅해 InitDb의 @PostConstruct 시딩이 동작함을 증명하는 스모크.
 *  테스트 기본값(wms.init-db.enabled=false)을 이 컨텍스트에서만 켠다. */
@SpringBootTest
@TestPropertySource(properties = "wms.init-db.enabled=true")
class InitDbSeedingSmokeTest {

    private static final List<Long> SEEDED_IDS = LongStream.rangeClosed(1, 20).boxed().toList();

    @Autowired InventoryRepository inventoryRepository;
    @Autowired InventoryTransactionRepository transactionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 시딩은 실제로 커밋된다 — 같은 물리 DB(wms_test)를 쓰는 다른 테스트를 오염시키지 않도록 지운다. */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from inventory_adjustment where product_id between 1 and 20");
        jdbcTemplate.update("delete from inventory where product_id between 1 and 20");
    }

    // 검증을 한 메서드에 모은다 — 시딩은 컨텍스트 기동 시 1회뿐이라 정리 후엔 재현되지 않는다.
    @Test
    void 부팅하면_상품_1_20이_시드되고_각각_OPENING_원장을_남긴다() {
        Map<Long, Inventory> seeded = inventoryRepository.findByProductIdIn(SEEDED_IDS).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));

        assertThat(seeded).hasSize(20);
        assertThat(seeded.get(1L).getOnHandQty()).isEqualTo(15);    // 15 * 1
        assertThat(seeded.get(7L).getOnHandQty()).isEqualTo(105);   // 15 * 7
        assertThat(seeded.get(20L).getOnHandQty()).isEqualTo(300);  // 15 * 20

        assertThat(SEEDED_IDS).allSatisfy(productId -> assertThat(
                transactionRepository.existsByProductIdAndType(productId, InventoryTransactionType.OPENING))
                .as("productId %d의 OPENING 원장", productId).isTrue());
    }
}
