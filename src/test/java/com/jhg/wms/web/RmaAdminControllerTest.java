package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.RmaDisposition;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.RmaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RmaAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class RmaAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RmaService rmaService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;

    // 행 전체 클릭은 tr의 data-href에 의존한다 — 이 속성이 빠지면 클릭이 조용히 죽으므로 렌더링을 검증한다.
    @Test
    void 반품목록의_각_행은_상세_링크를_data_href로_들고_있다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        when(rmaService.findAll(any())).thenReturn(List.of(rma));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/returns"))
                .andExpect(content().string(containsString("data-href=\"/admin/returns/7\"")))
                .andExpect(content().string(containsString("<a href=\"/admin/returns/7\"")));
    }

    // th:switch의 case를 하나라도 틀리면 조용히 '—'로 렌더링된다 — 값이 아니라 한글 라벨이 나오는지 본다.
    @Test
    void 완료된_반품_상세는_처분을_한글로_보여준다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        rma.receive();
        rma.getItems().get(0).inspect(2, RmaDisposition.RESTOCKED);
        rma.complete();
        when(rmaService.findById(7L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("재입고")))
                .andExpect(content().string(not(containsString("RESTOCKED"))));
    }

    // DB 계층 예외는 업무 예외가 아니라 컨트롤러 catch에 안 걸린다 — 흰 500 대신 목록으로 돌려보내는지 검증.
    @Test
    void 검수완료중_DB오류가_나면_500대신_목록으로_돌아간다() throws Exception {
        doThrow(new DataIntegrityViolationException("Value not permitted for column"))
                .when(rmaService).complete(eq(1L), anyMap());

        mockMvc.perform(post("/admin/returns/1/complete")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("items[0].itemId", "1")
                        .param("items[0].acceptedQuantity", "1")
                        .param("items[0].disposition", "RESTOCKED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/returns"))
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
