package com.jhg.wms.web;

import com.jhg.wms.service.InventoryLedgerAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 원장 추적 조회 REST. MCP 서버가 이것을 부른다.
 *
 * <p>계산하지 않는다 — 서비스에 위임하고 레코드를 그대로 직렬화할 뿐이다
 * ({@code CycleCountAnalyticsController}와 같은 규칙이다).
 *
 * <p>읽기 전용이고 경로가 {@code /api/**} 안인 것도 의도다 — apiChain의 basic 인증·
 * CSRF 비활성·401을 그대로 쓰고 {@code SecurityConfig}를 고치지 않는다.
 *
 * <p><b>응답에 행위자가 없다.</b> 서비스가 애초에 담지 않는다. 사람을 지목하는 판단은
 * 원장 화면(V7)에서 사람이 한다.
 *
 * <p>400 평문 오류 계약은 {@link AnalyticsErrorAdvice}가 담당한다.
 *
 * <p><b>소비자</b>: {@code mcp-server/wms_mcp/client.py}가 이 경로와 파라미터 이름을 그대로
 * 하드코딩해 부른다. 여기서 바꾸면 그쪽도 같이 고쳐야 한다(Java 테스트는 그 불일치를 잡지 못한다).
 */
@RestController
@RequestMapping("/api/analytics")
public class InventoryLedgerAnalyticsController {

    private final InventoryLedgerAnalyticsService inventoryLedgerAnalyticsService;

    public InventoryLedgerAnalyticsController(InventoryLedgerAnalyticsService inventoryLedgerAnalyticsService) {
        this.inventoryLedgerAnalyticsService = inventoryLedgerAnalyticsService;
    }

    @GetMapping("/inventory-ledger/product/{productId}")
    public InventoryLedgerAnalyticsService.LedgerReport ledger(
            @PathVariable Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return inventoryLedgerAnalyticsService.ledger(productId, from, to);
    }
}
