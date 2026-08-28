package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// InventoryControllerTest와 같은 이유로 DbUserDetailsService 목빈이 필요하다(webChain 로딩용).
@WebMvcTest(ShipmentController.class)
@Import(SecurityConfig.class)
class ShipmentControllerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-27T06:30:00.123456Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-08-28T01:00:00.123456Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 송장이_발급된_주문은_모든_필드를_반환한다() throws Exception {
        when(inventoryService.findShipment(202L)).thenReturn(Optional.of(new ShipmentResponse(
                202L, "MOCK", "테스트택배", "MOCK-202-20260827063000", ISSUED_AT, DELIVERED_AT)));

        mockMvc.perform(get("/api/shipments/202").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(202))
                .andExpect(jsonPath("$.carrierCode").value("MOCK"))
                .andExpect(jsonPath("$.carrierName").value("테스트택배"))
                .andExpect(jsonPath("$.trackingNumber").value("MOCK-202-20260827063000"))
                // 초 미만까지 그대로 나간다 — 초 단위 고정 패턴 파서를 쓰면 깨진다.
                .andExpect(jsonPath("$.issuedAt").value("2026-08-27T06:30:00.123456Z"))
                .andExpect(jsonPath("$.deliveredAt").value("2026-08-28T01:00:00.123456Z"));
    }

    @Test
    void 배송_중이면_deliveredAt은_null이다() throws Exception {
        when(inventoryService.findShipment(202L)).thenReturn(Optional.of(new ShipmentResponse(
                202L, "MOCK", "테스트택배", "MOCK-202-20260827063000", ISSUED_AT, null)));

        mockMvc.perform(get("/api/shipments/202").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                // 필드가 빠지는 게 아니라 null로 나간다 — OMS가 키 존재 여부로 분기하지 않아도 된다.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"deliveredAt\":null")))
                .andExpect(jsonPath("$.trackingNumber").value("MOCK-202-20260827063000"));
    }

    @Test
    void 예약이_없거나_송장_미발급이면_404다() throws Exception {
        when(inventoryService.findShipment(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/shipments/404").with(httpBasic("wms", "wms")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 인증이_없으면_401이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(get("/api/shipments/202"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(inventoryService);
    }
}
