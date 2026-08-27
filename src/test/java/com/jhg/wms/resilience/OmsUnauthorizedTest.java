package com.jhg.wms.resilience;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자격증명 오설정으로 OMS가 401을 돌려주는 상황.
 * 통지는 실패하지만 재고·원장은 커밋돼야 한다 — 인증 문제로 입고가 막히면 안 된다.
 */
@SpringBootTest
@DisplayName("OMS가 401을 줘도 재고와 원장은 커밋된다")
class OmsUnauthorizedTest {

    private static final long PID = 9502L;

    private static HttpServer stub;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @DynamicPropertySource
    static void 인증을_거부하는_OMS를_띄운다(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        stub.start();
        registry.add("oms.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void 스텁을_내린다() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void 통지가_401로_거부돼도_조정은_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        int after = inventoryService.adjust(PID, 5, "OMS 인증 실패 중 조정");

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
