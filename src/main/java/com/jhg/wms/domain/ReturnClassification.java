package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 반품 사유 자동 분류 결과. RmaReturn의 필드가 아니라 별도 테이블에 둔다 —
 * 분류는 RMA의 상태가 아니라 참고 정보이고, 도메인에 섞으면
 * "이 필드가 업무 규칙인가 힌트인가"가 흐려진다.
 */
@Entity
@Table(name = "return_classification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnClassification {

    private static final int EVIDENCE_MAX = 500;

    @Id @GeneratedValue
    @Column(name = "return_classification_id")
    private Long id;

    // 연관(@OneToOne)이 아니라 ID만 든다 — RmaReturn을 읽을 때 분류가 딸려오지 않게 해서
    // 업무 경로와 참고 경로를 물리적으로 갈라 둔다.
    @Column(nullable = false, unique = true)
    private Long rmaReturnId;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // DB 네이티브 ENUM 대신 VARCHAR — 값 추가 시 기존 컬럼이 거부하는 사고 방지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnCategory category;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Confidence confidence;

    @Column(length = EVIDENCE_MAX)
    private String evidence;

    // 기존 RmaDisposition을 재사용한다 — 나중에 실제 검수 결과와 대조할 때
    // 별도 enum이면 매핑이 필요해지지만 같은 타입이면 그냥 비교된다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    private RmaDisposition suggestedDisposition;

    @Column(nullable = false)
    private String model;

    // "AI 기능을 붙였는데 얼마 드는지 모른다"를 피하려고 건별로 남긴다.
    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private Instant classifiedAt;

    public static ReturnClassification create(Long rmaReturnId, ReturnCategory category,
                                              Confidence confidence, String evidence,
                                              RmaDisposition suggestedDisposition,
                                              String model, int inputTokens, int outputTokens) {
        if (rmaReturnId == null) throw new IllegalArgumentException("rmaReturnId는 필수입니다.");
        if (category == null) throw new IllegalArgumentException("category는 필수입니다.");
        if (confidence == null) throw new IllegalArgumentException("confidence는 필수입니다.");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model은 필수입니다.");

        ReturnClassification c = new ReturnClassification();
        c.rmaReturnId = rmaReturnId;
        c.category = category;
        c.confidence = confidence;
        // 근거 길이는 모델이 정하므로 우리가 통제하지 못한다 — 컬럼 길이에 맞춰 자른다.
        c.evidence = (evidence != null && evidence.length() > EVIDENCE_MAX)
                ? evidence.substring(0, EVIDENCE_MAX) : evidence;
        c.suggestedDisposition = suggestedDisposition;
        c.model = model;
        c.inputTokens = inputTokens;
        c.outputTokens = outputTokens;
        c.classifiedAt = Instant.now();
        return c;
    }
}
