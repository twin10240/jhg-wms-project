package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryRepository inventoryRepository;

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
}
