package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.web.InventoryRowResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryRepository inventoryRepository;
    @MockitoBean InventoryService inventoryService;

    @Test
    void 상품ID목록으로_가용수량_맵을_반환한다() throws Exception {
        Inventory i1 = Inventory.create(1L, 10);
        Inventory i2 = Inventory.create(2L, 5);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1, i2));

        mockMvc.perform(get("/api/inventory/availability").param("productIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.2").value(5));
    }

    @Test
    void 재고가_없는_상품ID는_응답에_포함되지_않는다() throws Exception {
        // productId=3은 WMS에 재고 없음 → 응답 맵에 미포함 (OMS 어댑터가 0으로 기본 처리)
        Inventory i1 = Inventory.create(1L, 10);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1));

        mockMvc.perform(get("/api/inventory/availability").param("productIds", "1,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.3").doesNotExist());
    }

    @Test
    void adjust_조정후_수량을_반환한다() throws Exception {
        when(inventoryService.adjust(1L, 5)).thenReturn(15);

        mockMvc.perform(post("/api/inventory/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"delta\":5,\"reason\":\"정기조사\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("15"));
    }

    @Test
    void adjust_잘못된_요청은_400을_반환한다() throws Exception {
        when(inventoryService.adjust(1L, -99)).thenThrow(new IllegalArgumentException("재고 부족"));

        mockMvc.perform(post("/api/inventory/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"delta\":-99,\"reason\":\"조정\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rows_전체_재고_목록을_반환한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, 10)));

        mockMvc.perform(get("/api/inventory/rows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].onHandQty").value(10));
    }

    @Test
    void reserve_서비스에_위임하고_결과를_반환한다() throws Exception {
        when(inventoryService.reserveAll(eq(1L), any())).thenReturn(true);

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void ship_서비스에_위임한다() throws Exception {
        mockMvc.perform(post("/api/inventory/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk());

        verify(inventoryService).shipAll(eq(1L), any());
    }

    @Test
    void release_서비스에_위임한다() throws Exception {
        mockMvc.perform(post("/api/inventory/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk());

        verify(inventoryService).releaseAll(eq(1L), any());
    }
}
