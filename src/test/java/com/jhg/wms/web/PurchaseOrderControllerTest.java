package com.jhg.wms.web;

import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PurchaseOrderController.class)
class PurchaseOrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PurchaseOrderService purchaseOrderService;

    @Test
    void 발주_목록을_반환한다() throws Exception {
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(po));

        mockMvc.perform(get("/api/purchase-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ORDERED"))
                .andExpect(jsonPath("$[0].memo").value("긴급"))
                .andExpect(jsonPath("$[0].items[0].productId").value(1))
                .andExpect(jsonPath("$[0].items[0].quantity").value(10));
    }

    @Test
    void 발주를_생성하고_응답을_반환한다() throws Exception {
        when(purchaseOrderService.create(anyList(), anyString())).thenReturn(7L);
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(po));

        mockMvc.perform(post("/api/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":1,\"quantity\":10}],\"memo\":\"긴급\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(purchaseOrderService).create(anyList(), eq("긴급"));
    }

    @Test
    void 발주_품목이_없으면_400을_반환한다() throws Exception {
        when(purchaseOrderService.create(anyList(), any()))
                .thenThrow(new IllegalArgumentException("발주 품목이 없습니다."));

        mockMvc.perform(post("/api/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[],\"memo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 입고하면_200을_반환한다() throws Exception {
        when(purchaseOrderService.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "7"))
                .andExpect(status().isOk());

        verify(purchaseOrderService).receive(7L);
    }

    @Test
    void 없는_발주를_입고하면_404를_반환한다() throws Exception {
        when(purchaseOrderService.receive(99L))
                .thenThrow(new IllegalArgumentException("발주가 없습니다: id=99"));

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 중복_입고는_409를_반환한다() throws Exception {
        when(purchaseOrderService.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "7"))
                .andExpect(status().isConflict());
    }
}
