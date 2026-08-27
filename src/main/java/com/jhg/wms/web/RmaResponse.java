package com.jhg.wms.web;

import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.domain.RmaReturnItem;

import java.util.List;

/**
 * 반품 조회 응답이자 OMS 상태 통지(`POST /api/return-status-events`) 페이로드.
 *
 * <p><b>{@code rmaId}는 WMS 로컬 식별자다 — 전역 유일하지 않다.</b> WMS DB 안에서만 유일한
 * 시퀀스 값이고 DB를 새로 만들면 1부터 다시 발급된다. 호출 측은 {@code requestKey}(호출 측이
 * 생성한 UUID, WMS가 유니크 제약으로 강제하고 접수 멱등의 기준으로 쓴다)로 상관관계를 잡아야 한다.
 * 그래서 이 레코드는 두 값을 항상 함께 싣는다.
 */
public record RmaResponse(
        Long rmaId,
        String requestKey,
        Long orderId,
        String status,
        List<ItemResponse> items
) {
    public record ItemResponse(
            Long orderItemId,
            Long productId,
            int requestedQuantity,
            int acceptedQuantity,
            String disposition
    ) {}

    public static RmaResponse from(RmaReturn rma) {
        return new RmaResponse(
                rma.getId(),
                rma.getRequestKey(),
                rma.getOrderId(),
                rma.getStatus().name(),
                rma.getItems().stream().map(RmaResponse::toItemResponse).toList()
        );
    }

    private static ItemResponse toItemResponse(RmaReturnItem item) {
        return new ItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getRequestedQuantity(),
                item.getAcceptedQuantity() != null ? item.getAcceptedQuantity() : 0,
                item.getDisposition() != null ? item.getDisposition().name() : null
        );
    }
}
