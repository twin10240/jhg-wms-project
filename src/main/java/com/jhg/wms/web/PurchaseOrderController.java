package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
            List<PurchaseOrderResponse.ItemResponse> items = req.lines().stream()
                    .map(l -> new PurchaseOrderResponse.ItemResponse(null, l.productId(), l.quantity()))
                    .toList();
            PurchaseOrderResponse response = new PurchaseOrderResponse(
                    poId, "ORDERED", req.memo(), LocalDateTime.now(), null, items);
            return ResponseEntity.ok(response);
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
