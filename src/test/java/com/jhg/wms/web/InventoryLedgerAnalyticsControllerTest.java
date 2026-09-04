package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.service.InventoryLedgerAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic.
@WebMvcTest(InventoryLedgerAnalyticsController.class)
@Import({SecurityConfig.class, AnalyticsErrorAdvice.class})
class InventoryLedgerAnalyticsControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 3);

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryLedgerAnalyticsService inventoryLedgerAnalyticsService;
    @MockitoBean DbUserDetailsService userDetailsService;

    private InventoryLedgerAnalyticsService.LedgerReport report(
            List<InventoryLedgerAnalyticsService.LedgerRow> rows, boolean truncated, long total) {
        return new InventoryLedgerAnalyticsService.LedgerReport(11L, FROM, TO, rows, truncated, total);
    }

    @Test
    void 원장을_그대로_낸다() throws Exception {
        var row = new InventoryLedgerAnalyticsService.LedgerRow(
                InventoryTransactionType.ADJUST, -1, 115, 114, "PO#7", "파손 폐기",
                LocalDateTime.of(2026, 9, 2, 10, 0));
        when(inventoryLedgerAnalyticsService.ledger(11L, FROM, TO))
                .thenReturn(report(List.of(row), false, 1));

        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-01").param("to", "2026-09-03")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(11))
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.to").value("2026-09-03"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].type").value("ADJUST"))
                .andExpect(jsonPath("$.rows[0].delta").value(-1))
                .andExpect(jsonPath("$.rows[0].beforeQty").value(115))
                .andExpect(jsonPath("$.rows[0].afterQty").value(114))
                .andExpect(jsonPath("$.rows[0].reference").value("PO#7"))
                .andExpect(jsonPath("$.rows[0].reason").value("파손 폐기"))
                .andExpect(jsonPath("$.rows[0].occurredAt").value("2026-09-02T10:00:00"));
    }

    @Test
    void 행위자는_응답에_없다() throws Exception {
        // 이 설계의 핵심 제약이다. 계약에서 뺐다는 사실을 테스트로 고정한다 —
        // 나중에 LedgerRow에 actor를 더하면 여기서 깨져야 한다.
        var row = new InventoryLedgerAnalyticsService.LedgerRow(
                InventoryTransactionType.ADJUST, -1, 115, 114, null, "파손",
                LocalDateTime.of(2026, 9, 2, 10, 0));
        when(inventoryLedgerAnalyticsService.ledger(11L, FROM, TO))
                .thenReturn(report(List.of(row), false, 1));

        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-01").param("to", "2026-09-03")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].actor").doesNotExist());
    }

    @Test
    void 잘렸으면_잘렸다고_낸다() throws Exception {
        when(inventoryLedgerAnalyticsService.ledger(11L, FROM, TO))
                .thenReturn(report(List.of(), true, 812));

        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-01").param("to", "2026-09-03")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.total").value(812));
    }

    @Test
    void 이동이_없으면_빈_목록이고_404가_아니다() throws Exception {
        when(inventoryLedgerAnalyticsService.ledger(11L, FROM, TO))
                .thenReturn(report(List.of(), false, 0));

        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-01").param("to", "2026-09-03")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isEmpty());
    }

    @Test
    void 날짜_형식이_틀리면_400_평문이다() throws Exception {
        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-13-01").param("to", "2026-09-03")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("YYYY-MM-DD")));
    }

    @Test
    void 역전된_범위는_400이고_500이_아니다() throws Exception {
        // 서비스의 ledger()가 실제로 던지는 예외다. 매핑이 없으면 500이 되고,
        // 모델은 "내 인자가 틀렸다"와 "창고가 고장났다"를 구분하지 못한다.
        doThrow(new IllegalArgumentException("조회 구간이 뒤집혔습니다: " + TO + " ~ " + FROM))
                .when(inventoryLedgerAnalyticsService).ledger(11L, TO, FROM);

        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-03").param("to", "2026-09-01")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                // 본문은 평문이다(README의 기존 API 오류 계약). 무엇을 고쳐야 할지 읽혀야 한다.
                .andExpect(content().string("조회 구간이 뒤집혔습니다: " + TO + " ~ " + FROM));
    }

    @Test
    void 인증_없이는_401이다() throws Exception {
        mockMvc.perform(get("/api/analytics/inventory-ledger/product/11")
                        .param("from", "2026-09-01").param("to", "2026-09-03"))
                .andExpect(status().isUnauthorized());
    }
}
