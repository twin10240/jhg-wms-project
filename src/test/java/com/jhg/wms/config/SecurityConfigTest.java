package com.jhg.wms.config;

import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.web.InventoryController;
import com.jhg.wms.web.ReplenishmentRequestController;
import com.jhg.wms.web.WmsAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WMS Basic 인증(SecurityConfig) 슬라이스 검증. API는 인증만 있으면 CSRF 없이 통과(서버간 호출),
 * 관리자 폼(admin/**)은 인증 + CSRF 둘 다 필요.
 */
@WebMvcTest(controllers = {InventoryController.class, ReplenishmentRequestController.class, WmsAdminController.class})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryRepository inventoryRepository;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean PurchaseOrderService purchaseOrderService;
    @MockitoBean ReplenishmentRequestService replenishmentRequestService;

    @Test
    void replenishmentApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/replenishment-requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증없이_API를_호출하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/inventory/availability").param("productIds", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF없이_관리자_폼을_제출하면_403을_반환한다() throws Exception {
        mockMvc.perform(post("/admin/inventory/adjust").with(httpBasic("wms", "wms"))
                        .param("productId", "1").param("delta", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증하면_API_호출이_정상_동작한다() throws Exception {
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of());

        mockMvc.perform(get("/api/inventory/availability").with(httpBasic("wms", "wms"))
                        .param("productIds", "1"))
                .andExpect(status().isOk());
    }
}
