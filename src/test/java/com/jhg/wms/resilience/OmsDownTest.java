package com.jhg.wms.resilience;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "상대가 죽어도 재고는 오염되지 않는다"의 증명.
 * 지금까지는 수동 관측 기록 한 번이 전부였다.
 *
 * <p>포트 1은 어떤 서비스도 듣지 않으므로 연결이 즉시 거부된다 — 죽은 OMS의 재현이다.
 * WireMock 같은 의존성을 쓰지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = "oms.base-url=http://localhost:1")
@DisplayName("OMS가 죽어도 재고와 원장은 커밋된다")
class OmsDownTest {

    private static final long PID = 9500L;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void 통지가_실패해도_조정은_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        int after = inventoryService.adjust(PID, 5, "OMS 다운 중 조정");

        assertThat(after).isEqualTo(15);
        // 람다 결과를 int로 받아둔다 — tx.execute()를 assertThat에 바로 넣으면 오버로드가 모호해진다.
        int committed = tx.execute(s ->
                inventoryRepository.findByProductId(PID).orElseThrow().getOnHandQty());
        assertThat(committed).isEqualTo(15);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> inventoryRepository.findByProductId(PID)
                .ifPresent(inventoryRepository::delete));
    }
}
