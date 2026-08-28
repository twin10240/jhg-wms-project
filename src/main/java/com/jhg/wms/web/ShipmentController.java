package com.jhg.wms.web;

import com.jhg.wms.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 송장 조회(읽기 전용). OMS가 송장·배송 상태 불일치를 확인·복구하는 용도다.
 * 조회는 아무것도 바꾸지 않는다 — 송장 재발급도, 출고 처리도 하지 않는다.
 * 인증은 `/api/**` 서비스 계정 Basic(SecurityConfig apiChain)을 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final InventoryService inventoryService;

    /** 예약이 없거나 아직 송장이 발급되지 않았으면 404 — 둘을 구분하지 않는다(OMS의 처리가 같다). */
    @GetMapping("/{orderId}")
    public ResponseEntity<ShipmentResponse> find(@PathVariable Long orderId) {
        return inventoryService.findShipment(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
