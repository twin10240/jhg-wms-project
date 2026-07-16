package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.jhg.wms.web.InventoryRowResponse;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;

    /**
     * 채널1: OMS가 메인 그리드 조립 시 호출한다.
     * productIds는 콤마 구분 문자열(예: "1,2,3"). 응답은 {productId: availableQty} 맵.
     * 재고가 없는 productId는 응답에 미포함 — OMS가 0으로 기본 처리한다.
     */
    @GetMapping("/availability")
    public Map<Long, Integer> availability(@RequestParam String productIds) {
        List<Long> ids = Arrays.stream(productIds.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .toList();
        return inventoryRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailableQty));
    }

    @GetMapping("/rows")
    public List<InventoryRowResponse> rows() {
        return inventoryService.findAllRows();
    }

    @PostMapping("/reserve")
    public boolean reserve(@RequestBody InventoryWriteRequest req) {
        return inventoryService.reserveAll(req.orderId(), req.items());
    }

    @PostMapping("/ship")
    public void ship(@RequestBody InventoryWriteRequest req) {
        inventoryService.shipAll(req.orderId(), req.items());
    }

    @PostMapping("/release")
    public void release(@RequestBody InventoryWriteRequest req) {
        inventoryService.releaseAll(req.orderId(), req.items());
    }

    /** 잘못된 요청(음수/0 수량 등) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** 예약 상태 충돌(예약 없음·해제 후 출고·출고 후 해제) → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
