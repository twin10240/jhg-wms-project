package com.jhg.wms.web;

import com.jhg.wms.service.ReservationAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 체류 조회 REST. <b>응답 필드 이름을 고정한다</b> — MCP 클라이언트가 이 이름을 그대로 읽고,
 * 스킬이 그 이름으로 보고서 규율을 적었다. 여기서 이름이 바뀌면 둘 다 조용히 어긋난다.
 */
// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic.
@WebMvcTest(ReservationAnalyticsController.class)
@Import(SecurityConfig.class)
class ReservationAnalyticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ReservationAnalyticsService service;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 체류_집계_응답_필드를_고정한다() throws Exception {
        given(service.dwell(any(), any())).willReturn(
                new ReservationAnalyticsService.DwellReport(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "endedAt",
                        new ReservationAnalyticsService.DwellStats(2, 120L, 240L, 240L),
                        new ReservationAnalyticsService.DwellStats(0, null, null, null),
                        3, 1));

        mockMvc.perform(get("/api/analytics/reservation-dwell")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("endedAt"))
                .andExpect(jsonPath("$.shipped.count").value(2))
                .andExpect(jsonPath("$.shipped.medianMinutes").value(120))
                .andExpect(jsonPath("$.released.medianMinutes").doesNotExist())
                .andExpect(jsonPath("$.stillOpen").value(3))
                .andExpect(jsonPath("$.excludedMissingCreatedAt").value(1));
    }

    @Test
    void 상품별_응답_필드를_고정한다() throws Exception {
        given(service.dwellByProduct(any(), any())).willReturn(List.of(
                new ReservationAnalyticsService.ProductDwell(11L, "볼펜", 2, 120L, 240L, 2, 0)));

        mockMvc.perform(get("/api/analytics/reservation-dwell-by-product")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(11))
                .andExpect(jsonPath("$[0].productName").value("볼펜"))
                .andExpect(jsonPath("$[0].occurrences").value(2))
                .andExpect(jsonPath("$[0].medianMinutes").value(120))
                .andExpect(jsonPath("$[0].maxMinutes").value(240))
                .andExpect(jsonPath("$[0].shippedCount").value(2))
                .andExpect(jsonPath("$[0].releasedCount").value(0));
    }

    @Test
    void 역전된_구간은_400_평문이다() throws Exception {
        given(service.dwell(any(), any()))
                .willThrow(new IllegalArgumentException("시작일이 종료일보다 뒤입니다."));

        mockMvc.perform(get("/api/analytics/reservation-dwell")
                        .param("from", "2026-09-30").param("to", "2026-09-01")
                        .with(httpBasic("wms", "wms")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("시작일이 종료일보다 뒤입니다."));
    }
}
