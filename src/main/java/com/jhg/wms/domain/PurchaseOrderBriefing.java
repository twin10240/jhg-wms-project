package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 생성된 발주 브리핑. {@link PurchaseOrderMemoClassification}과 같은 이유로 도메인에서
 * 떼어낸 별도 테이블이다 — 브리핑은 발주의 상태가 아니라 참고 정보이고, 도메인에 섞으면
 * "이 필드가 업무 규칙인가 힌트인가"가 흐려진다.
 *
 * <p>분류 둘과 달리 특정 발주에 매이지 않는다. 브리핑은 발주 하나가 아니라 화면 전체를
 * 읽은 것이라 {@code purchaseOrderId}가 없다.
 */
@Entity
@Table(name = "purchase_order_briefing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderBriefing {

    @Id @GeneratedValue
    @Column(name = "purchase_order_briefing_id")
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 그때 모델에게 준 패널 값(JSON). 채점의 근거 집합이라 본문과 반드시 같이 산다. */
    @Column(nullable = false, columnDefinition = "text")
    private String inputSnapshot;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private Instant createdAt;

    public static PurchaseOrderBriefing create(String body, String inputSnapshot,
                                               String model, int inputTokens, int outputTokens) {
        PurchaseOrderBriefing b = new PurchaseOrderBriefing();
        b.body = body;
        b.inputSnapshot = inputSnapshot;
        b.model = model;
        b.inputTokens = inputTokens;
        b.outputTokens = outputTokens;
        b.createdAt = Instant.now();
        return b;
    }
}
