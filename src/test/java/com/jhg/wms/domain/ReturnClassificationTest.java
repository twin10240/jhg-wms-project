package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnClassificationTest {

    private ReturnClassification create(String evidence) {
        return ReturnClassification.create(7L, ReturnCategory.DAMAGED, Confidence.HIGH,
                evidence, RmaDisposition.DISPOSED, "claude-haiku-4-5", 400, 120);
    }

    @Test
    void 생성시_분류시각이_기록된다() {
        ReturnClassification c = create("모서리가 깨져 있어요");

        assertThat(c.getRmaReturnId()).isEqualTo(7L);
        assertThat(c.getCategory()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(c.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(c.getEvidence()).isEqualTo("모서리가 깨져 있어요");
        assertThat(c.getSuggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
        assertThat(c.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(c.getInputTokens()).isEqualTo(400);
        assertThat(c.getOutputTokens()).isEqualTo(120);
        assertThat(c.getClassifiedAt()).isNotNull();
    }

    // 근거는 모델이 원문에서 인용하는 값이라 길이를 우리가 통제하지 못한다.
    // 컬럼 길이를 넘기면 배경 스레드에서 DataIntegrityViolation이 나므로 경계에서 자른다.
    @Test
    void 근거가_500자를_넘으면_잘라_저장한다() {
        ReturnClassification c = create("가".repeat(600));

        assertThat(c.getEvidence()).hasSize(500);
    }

    @Test
    void 근거는_없어도_된다() {
        assertThat(create(null).getEvidence()).isNull();
    }

    @Test
    void 필수값이_없으면_거부한다() {
        assertThatThrownBy(() -> ReturnClassification.create(null, ReturnCategory.OTHER,
                Confidence.LOW, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, null,
                Confidence.LOW, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, ReturnCategory.OTHER,
                null, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, ReturnCategory.OTHER,
                Confidence.LOW, "x", RmaDisposition.REJECTED, " ", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
