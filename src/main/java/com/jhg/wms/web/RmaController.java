package com.jhg.wms.web;

import com.jhg.wms.service.RmaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class RmaController {

    private final RmaService rmaService;

    @PostMapping
    public ResponseEntity<RmaResponse> create(@RequestBody CreateRmaRequest request) {
        RmaService.CreateResult result = rmaService.createReturn(request);
        RmaResponse body = RmaResponse.from(result.rma());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/{rmaId}")
    public RmaResponse get(@PathVariable Long rmaId) {
        return RmaResponse.from(rmaService.findById(rmaId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(RmaService.DuplicateKeyConflictException.class)
    public ResponseEntity<String> handleDuplicateKey(RmaService.DuplicateKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
