package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public List<PurchaseOrderResponse> list() {
        return purchaseOrderService.findAllWithItems().stream()
                .map(PurchaseOrderResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@RequestBody PurchaseOrderRequest req) {
        try {
            Long poId = purchaseOrderService.create(req.toServiceLines(), req.memo());
            PurchaseOrderResponse response = purchaseOrderService.findAllWithItems().stream()
                    .filter(po -> po.getId().equals(poId))
                    .findFirst()
                    .map(PurchaseOrderResponse::from)
                    .orElseThrow();
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/receive")
    public ResponseEntity<Void> receive(@RequestParam Long poId) {
        try {
            purchaseOrderService.receive(poId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }
}
