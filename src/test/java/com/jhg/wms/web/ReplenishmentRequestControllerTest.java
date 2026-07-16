package com.jhg.wms.web;

import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.ReplenishmentRequest;
import com.jhg.wms.domain.ReplenishmentRequestItem;
import com.jhg.wms.service.ReplenishmentRequestService;
import com.jhg.wms.service.ReplenishmentRequestService.RequestLine;
import com.jhg.wms.service.ReplenishmentRequestService.RequestResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReplenishmentRequestController.class)
@Import(SecurityConfig.class)
class ReplenishmentRequestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ReplenishmentRequestService service;

    @Test
    void postCreatesRequestAndMapsMultipleItems() throws Exception {
        UUID key = UUID.randomUUID();
        when(service.request(eq(key), eq("low stock"), anyList()))
                .thenReturn(new RequestResult(request(key, 11L), true));

        mockMvc.perform(post("/api/replenishment-requests").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"%s","reason":"low stock","items":[
                                  {"productId":1,"requestedQty":3},{"productId":2,"requestedQty":5}
                                ]}
                                """.formatted(key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.items.length()").value(2));

        verify(service).request(key, "low stock", List.of(new RequestLine(1L, 3), new RequestLine(2L, 5)));
    }

    @Test
    void idempotentPostReturnsOk() throws Exception {
        UUID key = UUID.randomUUID();
        when(service.request(eq(key), eq("reason"), anyList()))
                .thenReturn(new RequestResult(request(key, 12L), false));

        mockMvc.perform(post("/api/replenishment-requests").with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"%s\",\"reason\":\"reason\",\"items\":[{\"productId\":1,\"requestedQty\":3}]}"
                                .formatted(key)))
                .andExpect(status().isOk());
    }

    @Test
    void mapsServiceErrors() throws Exception {
        when(service.request(any(), any(), any())).thenThrow(new IllegalArgumentException("bad request"));
        performInvalidPost().andExpect(status().isBadRequest());

        reset(service);
        when(service.request(any(), any(), any())).thenThrow(new IllegalStateException("conflict"));
        performInvalidPost().andExpect(status().isConflict());
    }

    @Test
    void getMapsHistoryFieldsAndItems() throws Exception {
        UUID key = UUID.randomUUID();
        ReplenishmentRequest request = request(key, 13L);
        request.approve(21L, "  ordered  ");
        request.fulfill();
        when(service.findAll()).thenReturn(List.of(request));

        mockMvc.perform(get("/api/replenishment-requests").with(httpBasic("wms", "wms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(13))
                .andExpect(jsonPath("$[0].requestKey").value(key.toString()))
                .andExpect(jsonPath("$[0].reason").value("low stock"))
                .andExpect(jsonPath("$[0].status").value("FULFILLED"))
                .andExpect(jsonPath("$[0].requestedAt").exists())
                .andExpect(jsonPath("$[0].decidedAt").exists())
                .andExpect(jsonPath("$[0].fulfilledAt").exists())
                .andExpect(jsonPath("$[0].wmsMemo").value("ordered"))
                .andExpect(jsonPath("$[0].purchaseOrderId").value(21))
                .andExpect(jsonPath("$[0].items[0].productId").value(1))
                .andExpect(jsonPath("$[0].items[0].requestedQty").value(3))
                .andExpect(jsonPath("$[0].items[1].productId").value(2))
                .andExpect(jsonPath("$[0].items[1].requestedQty").value(5));
    }

    @Test
    void unauthenticatedRequestsReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/replenishment-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/replenishment-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions performInvalidPost() throws Exception {
        return mockMvc.perform(post("/api/replenishment-requests").with(httpBasic("wms", "wms"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":null,\"reason\":\"reason\",\"items\":[]}"));
    }

    private ReplenishmentRequest request(UUID key, Long id) {
        ReplenishmentRequest request = ReplenishmentRequest.create(key, "low stock",
                ReplenishmentRequestItem.create(1L, 3), ReplenishmentRequestItem.create(2L, 5));
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }
}
