package com.jhg.wms.web;

import com.jhg.wms.service.ReplenishmentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/replenishment-requests")
@RequiredArgsConstructor
public class ReplenishmentRequestController {

    private final ReplenishmentRequestService service;

    @PostMapping
    public ResponseEntity<ReplenishmentRequestResponse> request(@RequestBody ReplenishmentRequestPayload payload) {
        var result = service.request(payload.requestKey(), payload.reason(), payload.toServiceLines());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReplenishmentRequestResponse.from(result.request()));
    }

    @GetMapping
    public List<ReplenishmentRequestResponse> history() {
        return service.findAll().stream().map(ReplenishmentRequestResponse::from).toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
