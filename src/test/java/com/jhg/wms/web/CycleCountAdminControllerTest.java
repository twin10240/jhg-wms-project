package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CycleCountAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class CycleCountAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CycleCountService cycleCountService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;

    private CycleCount submitted() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        ReflectionTestUtils.setField(c, "id", 7L);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        c.recordCount(101L, 14);
        c.submit("operator");
        return c;
    }

    @Test
    void 상세는_상태를_한글로_보여준다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("승인 대기")))
                .andExpect(content().string(not(containsString("SUBMITTED"))));
    }

    // 서버 인가로 막히지만, 눌러야 403을 알게 되는 버튼은 그 자체로 결함이다.
    @Test
    void OPERATOR에게는_승인_반려_버튼이_보이지_않는다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(content().string(not(containsString("ccApprove"))))
                .andExpect(content().string(not(containsString("ccReject"))));
    }

    @Test
    void OPERATOR가_승인을_직접_POST하면_403() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());

        verify(cycleCountService, never()).approve(anyLong());
    }

    @Test
    void MANAGER는_승인할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("mgr").roles("MANAGER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).approve(7L);
    }

    @Test
    void OPERATOR가_반려를_직접_POST하면_403() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/reject")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("reason", "계수 오류"))
                .andExpect(status().isForbidden());

        verify(cycleCountService, never()).reject(anyLong(), any());
    }

    @Test
    void MANAGER는_반려할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/reject")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("reason", "계수 오류"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).reject(7L, "계수 오류");
    }

    @Test
    void 승인된_세션은_품목별_반영_결과를_보여준다() throws Exception {
        CycleCount approved = submitted();
        approved.approve("manager");
        when(cycleCountService.findById(7L)).thenReturn(approved);
        when(cycleCountService.appliedDeltas(7L)).thenReturn(java.util.Map.of(1L, -1));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 14, 0, 14)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반영 결과")))
                .andExpect(content().string(containsString("-1")));
    }

    @Test
    void 없는_실사_상세는_500이_아니라_404_화면이다() throws Exception {
        when(cycleCountService.findById(999L))
                .thenThrow(new CycleCountService.CycleCountNotFoundException(999L));

        mockMvc.perform(get("/admin/cycle-counts/999").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 겹치는_세션_생성은_에러메시지로_돌아간다() throws Exception {
        when(cycleCountService.open(any(), any()))
                .thenThrow(new IllegalStateException("이미 진행 중인 실사에 포함된 상품입니다. productId=[1]"));

        mockMvc.perform(post("/admin/cycle-counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("productIds", "1").param("memo", "겹치는 실사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
