package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.service.CycleCountAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic.
@WebMvcTest(CycleCountAnalyticsController.class)
@Import(SecurityConfig.class)
class CycleCountAnalyticsControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Autowired MockMvc mockMvc;
    @MockitoBean CycleCountAnalyticsService cycleCountAnalyticsService;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 정확도_보고서를_그대로_낸다() throws Exception {
        var report = new CycleCountAnalyticsService.AccuracyReport(
                FROM, TO, "createdAt",
                new CycleCountAnalyticsService.SessionCounts(0, 0, 4, 1, 5),
                19, 13, 13.0 / 19, 1, 1, 5, 9, 3);
        when(cycleCountAnalyticsService.accuracy(FROM, TO)).thenReturn(report);

        mockMvc.perform(get("/api/analytics/cycle-count-accuracy")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                // 구간 기준을 응답이 싣는다 — 승인 시각이 아니라 시작 시각이라는 사실
                .andExpect(jsonPath("$.basis").value("createdAt"))
                .andExpect(jsonPath("$.sessions.approved").value(4))
                .andExpect(jsonPath("$.sessions.rejected").value(1))
                .andExpect(jsonPath("$.matchedItems").value(13))
                // 반려분을 분모에서 뺐다는 사실이 응답에 있어야 보고서가 밝힐 수 있다
                .andExpect(jsonPath("$.excludedRejectedItems").value(3));
    }

    @Test
    void 잴_것이_없으면_정확도는_null로_나간다() throws Exception {
        var empty = new CycleCountAnalyticsService.AccuracyReport(
                FROM, TO, "createdAt",
                new CycleCountAnalyticsService.SessionCounts(0, 0, 0, 0, 0),
                0, 0, null, 0, 0, 0, 0, 0);
        when(cycleCountAnalyticsService.accuracy(FROM, TO)).thenReturn(empty);

        mockMvc.perform(get("/api/analytics/cycle-count-accuracy")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                // 키를 빼면 모델이 0으로 기본값을 넣는다. null로 나가야 "잴 것이 없다"가 전달된다.
                .andExpect(jsonPath("$.accuracy").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"accuracy\":null")));
    }

    @Test
    void 차이_목록을_그대로_낸다() throws Exception {
        var row = new CycleCountAnalyticsService.VarianceRow(
                6L, 44, 42, -2, LocalDateTime.of(2026, 9, 5, 10, 0));
        var variance = new CycleCountAnalyticsService.ProductVariance(3L, "상품 3", 2, -3, List.of(row));
        when(cycleCountAnalyticsService.variances(FROM, TO)).thenReturn(List.of(variance));

        mockMvc.perform(get("/api/analytics/cycle-count-variances")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(3))
                .andExpect(jsonPath("$[0].occurrences").value(2))
                .andExpect(jsonPath("$[0].netQty").value(-3))
                .andExpect(jsonPath("$[0].rows[0].diff").value(-2));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/analytics/cycle-count-accuracy")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 필수_파라미터가_없으면_평문_400이다() throws Exception {
        mockMvc.perform(get("/api/analytics/cycle-count-accuracy")
                        .param("to", "2026-09-30").with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("from")));
    }

    @Test
    void 날짜_형식이_틀리면_평문_400이다() throws Exception {
        mockMvc.perform(get("/api/analytics/cycle-count-variances")
                        .param("from", "2026-9-1").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("YYYY-MM-DD")));
    }
}
