package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 발주 메모 자동 분류 결과. PurchaseOrder의 필드가 아니라 별도 테이블에 둔다 —
 * {@link ReturnClassification}과 같은 이유로, 분류는 발주의 상태가 아니라 참고 정보이고
 * 도메인에 섞으면 "이 필드가 업무 규칙인가 힌트인가"가 흐려진다.
 *
 * 반품 쪽과 달리 처분 제안이 없다. 발주 메모를 읽고 제안할 다음 동작이 없기 때문이다 —
 * 없는 필드를 대칭 때문에 만들지 않는다.
 */
@Entity
@Table(name = "purchase_order_memo_classification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderMemoClassification {

    private static final int EVIDENCE_MAX = 500;

    @Id @GeneratedValue
    @Column(name = "purchase_order_memo_classification_id")
    private Long id;

    // 연관(@ManyToOne)이 아니라 ID만 든다 — 발주를 읽을 때 분류가 딸려오지 않게 해서
    // 업무 경로와 참고 경로를 물리적으로 갈라 둔다.
    @Column(nullable = false, unique = true)
    private Long purchaseOrderId;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // DB 네이티브 ENUM 대신 VARCHAR — 값 추가 시 기존 컬럼이 거부하는 사고 방지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderMemoCategory category;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Confidence confidence;

    @Column(length = EVIDENCE_MAX)
    private String evidence;

    @Column(nullable = false)
    private String model;

    // "AI 기능을 붙였는데 얼마 드는지 모른다"를 피하려고 건별로 남긴다.
    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private Instant classifiedAt;

    public static PurchaseOrderMemoClassification create(Long purchaseOrderId,
                                                         PurchaseOrderMemoCategory category,
                                                         Confidence confidence, String evidence,
                                                         String model, int inputTokens, int outputTokens) {
        if (purchaseOrderId == null) throw new IllegalArgumentException("purchaseOrderId는 필수입니다.");
        if (category == null) throw new IllegalArgumentException("category는 필수입니다.");
        if (confidence == null) throw new IllegalArgumentException("confidence는 필수입니다.");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model은 필수입니다.");

        PurchaseOrderMemoClassification c = new PurchaseOrderMemoClassification();
        c.purchaseOrderId = purchaseOrderId;
        c.category = category;
        c.confidence = confidence;
        // 근거 길이는 모델이 정하므로 우리가 통제하지 못한다 — 컬럼 길이에 맞춰 자른다.
        c.evidence = (evidence != null && evidence.length() > EVIDENCE_MAX)
                ? evidence.substring(0, EVIDENCE_MAX) : evidence;
        c.model = model;
        c.inputTokens = inputTokens;
        c.outputTokens = outputTokens;
        c.classifiedAt = Instant.now();
        return c;
    }
}
