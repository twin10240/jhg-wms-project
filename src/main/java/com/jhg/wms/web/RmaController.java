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

    // 없는 RMA는 400이 아니라 404 — OMS 보상 스윕이 "요청이 잘못됨"과 "그 rmaId가 없음"을 구분해
    // 처리하므로, 둘을 같은 코드로 뭉치면 호출자가 분기할 수 없다.
    @ExceptionHandler(RmaService.RmaNotFoundException.class)
    public ResponseEntity<String> handleNotFound(RmaService.RmaNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(RmaService.DuplicateKeyConflictException.class)
    public ResponseEntity<String> handleDuplicateKey(RmaService.DuplicateKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
