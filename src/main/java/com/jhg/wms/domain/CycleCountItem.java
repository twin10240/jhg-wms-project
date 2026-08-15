package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cycle_count_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CycleCountItem {

    @Id @GeneratedValue
    @Column(name = "cycle_count_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_count_id", nullable = false)
    private CycleCount cycleCount;

    @Column(nullable = false)
    private Long productId;

    /** 세션을 연 시점의 장부 수량. 표시 전용 — 차이 계산은 승인 시점 장부로 다시 한다.
     *  이 값으로 계산하면 실사 중 이동분이 이중 반영된다. */
    @Column(nullable = false)
    private int bookQtyAtOpen;

    /** 실물 수량. null = 미입력. 0은 "세어보니 없었다"는 유효한 결과다. */
    private Integer countedQty;

    static CycleCountItem create(CycleCount cycleCount, Long productId, int bookQtyAtOpen) {
        CycleCountItem item = new CycleCountItem();
        item.cycleCount = cycleCount;
        item.productId = productId;
        item.bookQtyAtOpen = bookQtyAtOpen;
        return item;
    }

    void record(Integer countedQty) {
        if (countedQty == null)
            throw new IllegalArgumentException("실물 수량을 입력해야 합니다. productId=" + productId);
        if (countedQty < 0)
            throw new IllegalArgumentException("실물 수량은 0 이상이어야 합니다. productId=" + productId);
        this.countedQty = countedQty;
    }
}
