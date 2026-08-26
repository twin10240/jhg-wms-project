package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CycleCountTest {

    private CycleCount opened() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        c.addItem(2L, 30);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        ReflectionTestUtils.setField(c.getItems().get(1), "id", 102L);
        return c;
    }

    @Test
    void 세션을_열면_OPEN이고_실물수량은_비어있다() {
        CycleCount c = opened();

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.OPEN);
        assertThat(c.getItems()).extracting(CycleCountItem::getCountedQty).containsOnlyNulls();
        assertThat(c.getItems()).extracting(CycleCountItem::getBookQtyAtOpen).containsExactly(15, 30);
    }

    // 0은 "세어보니 없었다"는 유효한 결과다. 미입력(null)과 섞이면 안 센 품목이 조용히 확정된다.
    @Test
    void 실물수량_0은_유효한_입력이다() {
        CycleCount c = opened();

        c.recordCount(101L, 0);

        assertThat(c.getItems().get(0).getCountedQty()).isZero();
    }

    @Test
    void 실물수량은_음수일_수_없다() {
        CycleCount c = opened();

        assertThatThrownBy(() -> c.recordCount(101L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이상");
    }

    @Test
    void 미입력_품목이_있으면_제출할_수_없다() {
        CycleCount c = opened();
        c.recordCount(101L, 14);

        assertThatThrownBy(() -> c.submit("operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실물 수량");
        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.OPEN);
    }

    @Test
    void 전_품목_입력하면_제출된다() {
        CycleCount c = opened();
        c.recordCount(101L, 14);
        c.recordCount(102L, 30);

        c.submit("operator");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
        assertThat(c.getSubmittedBy()).isEqualTo("operator");
    }

    @Test
    void 제출된_세션은_실물수량을_고칠_수_없다() {
        CycleCount c = submitted();

        assertThatThrownBy(() -> c.recordCount(101L, 99))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void OPEN을_바로_승인할_수_없다() {
        CycleCount c = opened();

        assertThatThrownBy(() -> c.approve("manager"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 승인하면_APPROVED이고_승인자가_남는다() {
        CycleCount c = submitted();

        c.approve("manager");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.APPROVED);
        assertThat(c.getApprovedBy()).isEqualTo("manager");
    }

    @Test
    void 반려하면_REJECTED이고_사유가_남는다() {
        CycleCount c = submitted();

        c.reject("manager", "계수 오류로 재실사 필요");

        assertThat(c.getStatus()).isEqualTo(CycleCountStatus.REJECTED);
        assertThat(c.getRejectReason()).isEqualTo("계수 오류로 재실사 필요");
    }

    // 종결 상태에서 다시 전이하면 "언제 확정됐는가"가 흐려진다.
    @Test
    void 승인된_세션은_다시_전이할_수_없다() {
        CycleCount c = submitted();
        c.approve("manager");

        assertThatThrownBy(() -> c.reject("manager", "번복")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.approve("manager")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 반려된_세션은_다시_전이할_수_없다() {
        CycleCount c = submitted();
        c.reject("manager", "재실사");

        assertThatThrownBy(() -> c.approve("manager")).isInstanceOf(IllegalStateException.class);
    }

    private CycleCount submitted() {
        CycleCount c = opened();
        c.recordCount(101L, 14);
        c.recordCount(102L, 30);
        c.submit("operator");
        return c;
    }

    @Test
    void 반려_사유가_비면_거부하고_상태가_바뀌지_않는다() {
        // 반려는 계수 작업을 무르는 결정이라 사유가 없으면 계수자가 무엇을 고칠지 모른다.
        for (String blank : new String[] {null, "", "   "}) {
            CycleCount c = submitted();
            assertThatThrownBy(() -> c.reject("manager", blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("반려 사유");
            assertThat(c.getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
            assertThat(c.getRejectedBy()).isNull();
        }
    }

    @Test
    void 반려_사유의_앞뒤_공백은_제거된다() {
        CycleCount c = submitted();
        c.reject("manager", "  재계수 필요  ");
        assertThat(c.getRejectReason()).isEqualTo("재계수 필요");
    }
}
