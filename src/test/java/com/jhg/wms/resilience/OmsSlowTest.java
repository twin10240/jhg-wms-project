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
 * read-timeout: 2s 가 실제로 끊는지의 증명.
 * 설정은 application.yml에 있었지만 동작 증거가 없었다.
 *
 * <p>느린 OMS는 JDK 내장 HttpServer로 만든다 — 새 의존성이 필요 없다.
 */
@SpringBootTest
@DisplayName("OMS가 느려도 타임아웃으로 끊고 재고는 커밋된다")
class OmsSlowTest {

    private static final long PID = 9501L;
    private static final int OMS_DELAY_MS = 3000;   // read-timeout 2s보다 길게

    private static HttpServer stub;

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @DynamicPropertySource
    static void 느린_OMS를_띄운다(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            try {
                Thread.sleep(OMS_DELAY_MS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stub.start();
        registry.add("oms.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void 스텁을_내린다() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void 느린_OMS는_2초_타임아웃으로_끊기고_재고는_커밋된다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.save(Inventory.create(PID, "테스트상품", 10)));

        long startedAt = System.currentTimeMillis();
        int after = inventoryService.adjust(PID, 5, "OMS 지연 중 조정");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(after).isEqualTo(15);
        // 상한만 단언한다. "정확히 2초"는 타이밍 의존이라 플레이키하다.
        // 3초 응답을 끝까지 기다렸다면 이 선을 넘는다.
        assertThat(elapsed)
                .as("read-timeout 2s가 동작하지 않고 OMS 응답(%dms)을 끝까지 기다렸다", OMS_DELAY_MS)
                .isLessThan(OMS_DELAY_MS - 200);
    }

    @AfterEach
    void 정리() {
        tx.executeWithoutResult(s -> inventoryRepository.findByProductId(PID)
                .ifPresent(inventoryRepository::delete));
    }
}
