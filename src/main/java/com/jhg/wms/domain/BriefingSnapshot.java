package com.jhg.wms.domain;

import com.jhg.wms.service.PurchaseOrderAdviceService.ProductAdvice;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 브리핑 생성에 쓴 입력. 모델에게 준 값이자 <b>채점의 근거 집합</b>이다.
 *
 * <p>이 타입을 저장하는 이유가 설계의 핵심이다. 재고는 계속 변하므로 브리핑을 나중에 열면
 * 화면의 패널 값은 이미 다른 값이다. 스냅샷이 없으면 "이 문장의 숫자가 맞았나"를 영영
 * 판정할 수 없고, 숫자 대조 채점기가 먹을 것이 없어 평가 자체가 성립하지 않는다.
 *
 * <p>{@link ProductAdvice}를 그대로 저장하지 않고 좁힌다 — onHand·reserved는 브리핑이
 * 쓸 값이 아니라 available만 있으면 되고, 근거 집합이 넓어지면 환각이 우연히 통과한다.
 */
public record BriefingSnapshot(LocalDate generatedOn, List<Row> rows) {

    /**
     * @param daysToStockout 일평균이 0이면 null이다 — 0일이 아니라 잴 것이 없다는 뜻이다.
     *                       {@link ProductAdvice}의 규약을 그대로 잇는다.
     * @param lastOrderStatus 직전 발주가 없으면 null. 입고 여부를 파생시킬 수 없는 원본 데이터라
     *                        {@code daysSinceLastOrder}와 달리 접근자가 아니라 실제 필드다 —
     *                        측정 3회차에서 모델이 "직전 발주 100개를 받았으나"라고 입고를
     *                        단정한 사고 이후 추가했다. 표에 없으면 모델이 지어낸다.
     * @param lastOrderReceivedOn 상태가 RECEIVED·PARTIALLY_RECEIVED가 아니면 null(= 미입고).
     */
    public record Row(Long productId, String productName,
                      int shippedQty, long sampleDays, double dailyAverage,
                      int availableQty, Double daysToStockout,
                      Long lastOrderId, LocalDate lastOrderedOn, Integer lastOrderQty,
                      PurchaseOrderStatus lastOrderStatus, LocalDate lastOrderReceivedOn) {}

    /** 근거 패널이 이미 소진 임박 순으로 정렬해 주므로 여기서 다시 정렬하지 않는다. */
    public static BriefingSnapshot of(LocalDate generatedOn, List<ProductAdvice> advice, int topN) {
        List<Row> rows = advice.stream().limit(topN).map(a -> new Row(
                a.productId(), a.productName(),
                a.shippedQty(), a.sampleDays(), a.dailyAverage(),
                a.availableQty(), a.daysToStockout(),
                a.lastOrder() == null ? null : a.lastOrder().purchaseOrderId(),
                a.lastOrder() == null ? null : a.lastOrder().orderedOn(),
                a.lastOrder() == null ? null : a.lastOrder().quantity(),
                a.lastOrder() == null ? null : a.lastOrder().status(),
                a.lastOrder() == null ? null : a.lastOrder().receivedOn())).toList();
        return new BriefingSnapshot(generatedOn, rows);
    }

    /**
     * 직전 발주 이후 경과일. 회귀 평가 두 차례에서 모델이 매번 지어낸 값이 정확히 이것이었다
     * — 표에 없어서 계산했다. 저장 필드가 아니라 파생 접근자인 이유는 클래스 javadoc 참조.
     */
    public Long daysSinceLastOrder(Row r) {
        return r.lastOrderedOn() == null ? null : ChronoUnit.DAYS.between(r.lastOrderedOn(), generatedOn());
    }
}
