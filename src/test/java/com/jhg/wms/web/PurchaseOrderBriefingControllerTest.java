package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// @Import 없이 @WithMockUser만 쓰면 스프링 시큐리티 기본 자동설정(폼 로그인 + httpBasic)이 붙어
// 비인증 요청이 302가 아니라 401로 응답한다 — 이 저장소의 실제 webChain(SecurityConfig)은
// httpBasic 없이 폼 로그인만 쓰므로 항상 302다. 실제 보안 동작과 같게 SecurityConfig를 들여온다
// (WmsAdminControllerTest·CycleCountAdminControllerTest와 같은 관례).
@WebMvcTest(WmsAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class PurchaseOrderBriefingControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean PurchaseOrderBriefingService briefingService;
    @MockitoBean PurchaseOrderAdviceService purchaseOrderAdviceService;
    @MockitoBean PurchaseOrderService purchaseOrderService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;
    // WmsAdminController가 의존하는 나머지 빈들 — @WebMvcTest는 전부 목이 있어야 뜬다.
    @MockitoBean PurchaseOrderMemoClassificationService memoClassificationService;
    @MockitoBean ReplenishmentRequestService replenishmentRequestService;
    @MockitoBean RmaService rmaService;
    @MockitoBean CycleCountService cycleCountService;
    @MockitoBean ReturnAnalyticsService returnAnalyticsService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 브리핑_생성은_발주_목록으로_리다이렉트한다() throws Exception {
        given(briefingService.generateAndSave(any(), any())).willReturn(true);

        mvc.perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    // 실패해도 화면은 그대로 돌아간다. 브리핑이 없어도 발주 업무는 돈다.
    @Test
    @WithMockUser(roles = "ADMIN")
    void 생성_실패는_에러_메시지만_남기고_같은_화면으로_돌아간다() throws Exception {
        given(briefingService.generateAndSave(any(), any())).willReturn(false);

        mvc.perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 인증_없이는_거부한다() throws Exception {
        // is3xxRedirection()만 보면 시큐리티를 통째로 빼도 통과한다 — 실제로 /login으로
        // 보내는지(webChain의 formLogin.loginPage) 목적지까지 확인한다.
        mvc.perform(post("/admin/purchase-orders/briefing").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
