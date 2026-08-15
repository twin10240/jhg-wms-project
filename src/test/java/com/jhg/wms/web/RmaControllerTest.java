package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.*;
import com.jhg.wms.service.RmaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RmaController.class)
@Import(SecurityConfig.class)
class RmaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RmaService rmaService;
    @MockitoBean DbUserDetailsService userDetailsService;

    private RmaReturn stubRma() {
        RmaReturn rma = RmaReturn.create("test-key", 100L, "불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 30L);
        return rma;
    }

    @Test
    void 신규접수는_201을_반환한다() throws Exception {
        RmaReturn rma = stubRma();
        when(rmaService.createReturn(any()))
                .thenReturn(new RmaService.CreateResult(true, rma));

        mockMvc.perform(post("/api/returns").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"test-key","orderId":100,"reason":"불량",
                                 "items":[{"orderItemId":501,"productId":1,"quantity":2}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestKey").value("test-key"))
                .andExpect(jsonPath("$.orderId").value(100))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.items[0].orderItemId").value(501))
                .andExpect(jsonPath("$.items[0].requestedQuantity").value(2));
    }

    @Test
    void 멱등요청은_200을_반환한다() throws Exception {
        RmaReturn rma = stubRma();
        when(rmaService.createReturn(any()))
                .thenReturn(new RmaService.CreateResult(false, rma));

        mockMvc.perform(post("/api/returns").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"test-key","orderId":100,"reason":"불량",
                                 "items":[{"orderItemId":501,"productId":1,"quantity":2}]}"""))
                .andExpect(status().isOk());
    }

    @Test
    void 중복키_다른내용은_409를_반환한다() throws Exception {
        when(rmaService.createReturn(any()))
                .thenThrow(new RmaService.DuplicateKeyConflictException("test-key"));

        mockMvc.perform(post("/api/returns").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"test-key","orderId":100,"reason":"변심",
                                 "items":[{"orderItemId":501,"productId":1,"quantity":2}]}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void 잘못된_요청은_400을_반환한다() throws Exception {
        when(rmaService.createReturn(any()))
                .thenThrow(new IllegalArgumentException("수량은 1 이상이어야 합니다."));

        mockMvc.perform(post("/api/returns").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"test-key","orderId":100,"reason":"불량",
                                 "items":[{"orderItemId":501,"productId":1,"quantity":0}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 단건조회_정상() throws Exception {
        RmaReturn rma = stubRma();
        when(rmaService.findById(any())).thenReturn(rma);

        mockMvc.perform(get("/api/returns/1").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestKey").value("test-key"))
                .andExpect(jsonPath("$.items[0].acceptedQuantity").value(0))
                .andExpect(jsonPath("$.items[0].disposition").doesNotExist());
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(post("/api/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 계약테스트_JSON_필드명이_정확하다() throws Exception {
        RmaReturn rma = stubRma();
        when(rmaService.createReturn(any()))
                .thenReturn(new RmaService.CreateResult(true, rma));

        mockMvc.perform(post("/api/returns").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"k1","orderId":100,"reason":"불량",
                                 "items":[{"orderItemId":501,"productId":1,"quantity":2}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rmaId").exists())
                .andExpect(jsonPath("$.requestKey").exists())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].orderItemId").exists())
                .andExpect(jsonPath("$.items[0].productId").exists())
                .andExpect(jsonPath("$.items[0].requestedQuantity").exists())
                .andExpect(jsonPath("$.items[0].acceptedQuantity").exists())
                .andExpect(jsonPath("$.items[0].disposition").doesNotExist());
    }
}
