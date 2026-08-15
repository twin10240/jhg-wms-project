package com.jhg.wms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rma_return_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RmaReturnItem {

    @Id @GeneratedValue
    @Column(name = "rma_return_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rma_return_id", nullable = false)
    private RmaReturn rmaReturn;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int requestedQuantity;

    private Integer acceptedQuantity;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // H2 네이티브 ENUM 회피 — 값 추가 시 기존 컬럼이 거부하는 사고 방지

    @Enumerated(EnumType.STRING)
    private RmaDisposition disposition;

    static RmaReturnItem create(RmaReturn rmaReturn, Long orderItemId, Long productId, int requestedQuantity) {
        RmaReturnItem item = new RmaReturnItem();
        item.rmaReturn = rmaReturn;
        item.orderItemId = orderItemId;
        item.productId = productId;
        item.requestedQuantity = requestedQuantity;
        return item;
    }

    public void inspect(int acceptedQuantity, RmaDisposition disposition) {
        if (acceptedQuantity < 0 || acceptedQuantity > requestedQuantity)
            throw new IllegalArgumentException(
                    "승인 수량은 0 이상 요청 수량(" + requestedQuantity + ") 이하여야 합니다.");
        if (disposition == null)
            throw new IllegalArgumentException("처분을 지정해야 합니다.");
        if (acceptedQuantity == 0 && disposition != RmaDisposition.REJECTED)
            throw new IllegalArgumentException("승인 수량이 0이면 처분은 REJECTED여야 합니다.");
        if (acceptedQuantity > 0 && disposition == RmaDisposition.REJECTED)
            throw new IllegalArgumentException("승인 수량이 있으면 처분은 REJECTED일 수 없습니다.");
        this.acceptedQuantity = acceptedQuantity;
        this.disposition = disposition;
    }
}
