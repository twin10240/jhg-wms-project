package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryRepository inventoryRepository;

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
}
