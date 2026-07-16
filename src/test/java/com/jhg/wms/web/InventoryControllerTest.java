package com.jhg.wms.web;

import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.web.InventoryRowResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.mockito.ArgumentCaptor;

// API 엔드포인트는 CSRF 예외(SecurityConfig)라 인증만 필요 — 모든 호출에 httpBasic("wms","wms") 부여.
@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryRepository inventoryRepository;
    @MockitoBean InventoryService inventoryService;

    @Test
    void 상품ID목록으로_가용수량_맵을_반환한다() throws Exception {
        Inventory i1 = Inventory.create(1L, 10);
        Inventory i2 = Inventory.create(2L, 5);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1, i2));

        mockMvc.perform(get("/api/inventory/availability").with(httpBasic("wms", "wms")).param("productIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.2").value(5));
    }

    @Test
    void 재고가_없는_상품ID는_응답에_포함되지_않는다() throws Exception {
        // productId=3은 WMS에 재고 없음 → 응답 맵에 미포함 (OMS 어댑터가 0으로 기본 처리)
        Inventory i1 = Inventory.create(1L, 10);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1));

        mockMvc.perform(get("/api/inventory/availability").with(httpBasic("wms", "wms")).param("productIds", "1,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.3").doesNotExist());
    }

    @Test
    void rows_전체_재고_목록을_반환한다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/api/inventory/rows").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].productName").value("상품 1"))
                .andExpect(jsonPath("$[0].onHandQty").value(10))
                .andExpect(jsonPath("$[0].reservedQty").value(3))
                .andExpect(jsonPath("$[0].availableQty").value(7));
    }

    @Test
    void reserve_서비스에_위임하고_결과를_반환한다() throws Exception {
        when(inventoryService.reserveAll(eq(1L), any())).thenReturn(true);

        mockMvc.perform(post("/api/inventory/reserve").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void ship_서비스에_위임한다() throws Exception {
        mockMvc.perform(post("/api/inventory/ship").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk());

        verify(inventoryService).shipAll(eq(1L), any());
    }

    @Test
    void release_서비스에_위임한다() throws Exception {
        mockMvc.perform(post("/api/inventory/release").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk());

        verify(inventoryService).releaseAll(eq(1L), any());
    }

    @Test
    void ship_상태충돌은_409를_반환한다() throws Exception {
        doThrow(new IllegalStateException("예약이 없어 출고할 수 없습니다."))
                .when(inventoryService).shipAll(eq(1L), any());

        mockMvc.perform(post("/api/inventory/ship").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void release_상태충돌은_409를_반환한다() throws Exception {
        doThrow(new IllegalStateException("출고된 예약은 해제할 수 없습니다."))
                .when(inventoryService).releaseAll(eq(1L), any());

        mockMvc.perform(post("/api/inventory/release").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":3}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reserve_잘못된_수량은_400을_반환한다() throws Exception {
        when(inventoryService.reserveAll(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("수량은 1 이상이어야 합니다."));

        mockMvc.perform(post("/api/inventory/reserve").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":{\"1\":-3}}"))
                .andExpect(status().isBadRequest());
    }

    // ── T2: 계약 테스트(OMS↔WMS JSON 필드명 드리프트 방지) ────────────────
    // OMS가 보내는 JSON 형식: {"orderId":99,"items":{"1":3}}
    // InventoryController와 InventoryWriteRequest가 정확한 필드명으로 역직렬화하는지 검증.
    // 필드명이 어긋나면 서비스가 호출되지 않거나 null 값이 전달되어 validation error 발생.

    @Test
    void reserve_정확한_필드명_orderId_items로_역직렬화하고_서비스에_전달한다() throws Exception {
        // OMS 어댑터가 보내는 정확한 JSON: {"orderId":99,"items":{"1":3}}
        // InventoryWriteRequest가 이 JSON을 정확히 역직렬화하고,
        // InventoryController가 reserveAll(99L, Map.of(1L, 3))으로 호출하는지 검증
        ArgumentCaptor<Long> orderIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Map> itemsCaptor = ArgumentCaptor.forClass(Map.class);
        when(inventoryService.reserveAll(anyLong(), anyMap())).thenReturn(true);

        mockMvc.perform(post("/api/inventory/reserve").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":99,\"items\":{\"1\":3}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(inventoryService).reserveAll(orderIdCaptor.capture(), itemsCaptor.capture());

        // 정확한 인자 검증: 필드명 드리프트 방지
        assertThat(orderIdCaptor.getValue()).isEqualTo(99L);
        assertThat(itemsCaptor.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3));
    }

    @Test
    void reserve_복수상품도_정확하게_역직렬화한다() throws Exception {
        // 여러 상품 예약: orderId=99, items={1:3, 2:5, 3:2}
        // OMS 어댑터가 보내는 JSON: {"orderId":99,"items":{"1":3,"2":5,"3":2}}
        ArgumentCaptor<Long> orderIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Map> itemsCaptor = ArgumentCaptor.forClass(Map.class);
        when(inventoryService.reserveAll(anyLong(), anyMap())).thenReturn(true);

        mockMvc.perform(post("/api/inventory/reserve").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":99,\"items\":{\"1\":3,\"2\":5,\"3\":2}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(inventoryService).reserveAll(orderIdCaptor.capture(), itemsCaptor.capture());
        assertThat(orderIdCaptor.getValue()).isEqualTo(99L);
        assertThat(itemsCaptor.getValue())
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3, 2L, 5, 3L, 2));
    }
}
