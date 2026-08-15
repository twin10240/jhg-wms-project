package com.jhg.wms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rma_return")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RmaReturn {

    @Id @GeneratedValue
    @Column(name = "rma_return_id")
    private Long id;

    @Column(unique = true, nullable = false)
    private String requestKey;

    @Column(nullable = false)
    private Long orderId;

    private String reason;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // H2 네이티브 ENUM 회피 — 값 추가 시 기존 컬럼이 거부하는 사고 방지

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RmaStatus status;

    @OneToMany(mappedBy = "rmaReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RmaReturnItem> items = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime receivedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;

    public static RmaReturn create(String requestKey, Long orderId, String reason) {
        RmaReturn r = new RmaReturn();
        r.requestKey = requestKey;
        r.orderId = orderId;
        r.reason = reason;
        r.status = RmaStatus.REQUESTED;
        r.requestedAt = LocalDateTime.now();
        return r;
    }

    public void addItem(Long orderItemId, Long productId, int requestedQuantity) {
        items.add(RmaReturnItem.create(this, orderItemId, productId, requestedQuantity));
    }

    public void receive() {
        if (status != RmaStatus.REQUESTED)
            throw new IllegalStateException("REQUESTED 상태에서만 입고 처리할 수 있습니다.");
        status = RmaStatus.RECEIVED;
        receivedAt = LocalDateTime.now();
    }

    public void complete() {
        if (status != RmaStatus.RECEIVED)
            throw new IllegalStateException("RECEIVED 상태에서만 완료할 수 있습니다.");
        status = RmaStatus.COMPLETED;
        completedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (status != RmaStatus.REQUESTED)
            throw new IllegalStateException("REQUESTED 상태에서만 취소할 수 있습니다.");
        status = RmaStatus.CANCELLED;
        cancelledAt = LocalDateTime.now();
    }
}
