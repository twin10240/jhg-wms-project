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

/** 실사 세션. 계수(OPERATOR)와 승인(MANAGER)을 분리해, 센 사람이 스스로 장부를 고치지 못하게 한다. */
@Entity
@Table(name = "cycle_count")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CycleCount {

    @Id @GeneratedValue
    @Column(name = "cycle_count_id")
    private Long id;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // H2 네이티브 ENUM 회피 — 값 추가 시 기존 컬럼이 거부하는 사고 방지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CycleCountStatus status;

    private String memo;

    @OneToMany(mappedBy = "cycleCount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CycleCountItem> items = new ArrayList<>();

    @Column(nullable = false)
    private String createdBy;
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private String submittedBy;
    private LocalDateTime submittedAt;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String rejectedBy;
    private LocalDateTime rejectedAt;
    private String rejectReason;

    public static CycleCount open(String actor, String memo) {
        CycleCount c = new CycleCount();
        c.status = CycleCountStatus.OPEN;
        c.memo = memo;
        c.createdBy = actor;
        c.createdAt = LocalDateTime.now();
        return c;
    }

    public void addItem(Long productId, int bookQtyAtOpen) {
        requireOpen();
        items.add(CycleCountItem.create(this, productId, bookQtyAtOpen));
    }

    public void recordCount(Long itemId, Integer countedQty) {
        requireOpen();
        items.stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 세션의 품목이 아닙니다. itemId=" + itemId))
                .record(countedQty);
    }

    public void submit(String actor) {
        requireOpen();
        boolean missing = items.stream().anyMatch(i -> i.getCountedQty() == null);
        if (missing)
            throw new IllegalArgumentException("모든 품목의 실물 수량을 입력해야 합니다.");
        status = CycleCountStatus.SUBMITTED;
        submittedBy = actor;
        submittedAt = LocalDateTime.now();
    }

    public void approve(String actor) {
        requireSubmitted();
        status = CycleCountStatus.APPROVED;
        approvedBy = actor;
        approvedAt = LocalDateTime.now();
    }

    /** 반려는 계수 작업을 통째로 무르는 결정이다 — 사유가 없으면 계수자는 무엇을 고쳐야 할지 모른다. */
    public void reject(String actor, String reason) {
        requireSubmitted();
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("반려 사유를 입력해야 합니다.");
        status = CycleCountStatus.REJECTED;
        rejectedBy = actor;
        rejectedAt = LocalDateTime.now();
        rejectReason = reason.trim();
    }

    private void requireOpen() {
        if (status != CycleCountStatus.OPEN)
            throw new IllegalStateException("작성 중인 실사에서만 할 수 있습니다.");
    }

    private void requireSubmitted() {
        if (status != CycleCountStatus.SUBMITTED)
            throw new IllegalStateException("승인 대기 상태에서만 승인·반려할 수 있습니다.");
    }
}
