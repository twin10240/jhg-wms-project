package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnOwnerArea;
import com.jhg.wms.service.ReturnAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic("wms","wms").
// SecurityConfig가 webChain도 등록하고 webChain이 DbUserDetailsService를 요구하므로
// 슬라이스 컨텍스트 로딩용 목빈이 필요하다(직접 호출되지는 않는다).
@WebMvcTest(ReturnAnalyticsController.class)
@Import(SecurityConfig.class)
class ReturnAnalyticsControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired MockMvc mockMvc;
    @MockitoBean ReturnAnalyticsService returnAnalyticsService;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 반품률_보고서를_그대로_낸다() throws Exception {
        var row = new ReturnAnalyticsService.ProductReturnRate(11L, "상품 11", 50, 7, 0.14);
        when(returnAnalyticsService.productReturnRates(FROM, TO))
                .thenReturn(new ReturnAnalyticsService.ReturnRateReport(FROM, TO, 2, List.of(row), 3));

        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                // 날짜는 ISO 문자열이어야 한다. 타임스탬프로 나가면 MCP 서버가 다시 파싱해야 한다.
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.to").value("2026-08-31"))
                .andExpect(jsonPath("$.observedDays").value(2))
                // 관찰 경과일과 주문 연결 불가 출고 수는 보고서가 분모를 밝히는 근거다. 빠지면 안 된다.
                .andExpect(jsonPath("$.unlinkedShipRows").value(3))
                .andExpect(jsonPath("$.rows[0].productId").value(11))
                .andExpect(jsonPath("$.rows[0].productName").value("상품 11"))
                .andExpect(jsonPath("$.rows[0].shippedQty").value(50))
                .andExpect(jsonPath("$.rows[0].returnedQty").value(7))
                .andExpect(jsonPath("$.rows[0].returnRate").value(0.14));
    }

    @Test
    void 범주_분해를_그대로_낸다() throws Exception {
        var damaged = new ReturnAnalyticsService.CategoryCount(
                ReturnCategory.DAMAGED, ReturnOwnerArea.PACKAGING, 4);
        var mind = new ReturnAnalyticsService.CategoryCount(
                ReturnCategory.CHANGED_MIND, ReturnOwnerArea.PRODUCT_INFO, 12);
        when(returnAnalyticsService.categoryBreakdown(FROM, TO))
                .thenReturn(new ReturnAnalyticsService.CategoryBreakdown(List.of(damaged, mind), 5, 21));

        mockMvc.perform(get("/api/analytics/return-categories")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                // enum은 name()으로 나가야 한다. 한글 label()로 나가면 모델이 범주를 문자열로 비교하지 못한다.
                .andExpect(jsonPath("$.counts[0].category").value("DAMAGED"))
                .andExpect(jsonPath("$.counts[0].ownerArea").value("PACKAGING"))
                .andExpect(jsonPath("$.counts[0].count").value(4))
                .andExpect(jsonPath("$.counts[1].category").value("CHANGED_MIND"))
                // 미분류와 전체는 분모를 밝히는 값이다 — Skill이 "미분류를 반드시 밝힌다"를 지키려면 있어야 한다.
                .andExpect(jsonPath("$.unclassified").value(5))
                .andExpect(jsonPath("$.totalReturns").value(21));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void from이_없으면_400이다() throws Exception {
        // 기본값을 두지 않는다는 결정을 여기서 고정한다. 화면과 달리 보고서는 분모가 분명해야 한다.
        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .with(httpBasic("wms", "wms"))
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest());
    }
}
