package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseOrderBriefingTest {

    private PurchaseOrderBriefing create(String body, String inputSnapshot) {
        return PurchaseOrderBriefing.create(body, inputSnapshot, "claude-haiku-4-5", 400, 120);
    }

    @Test
    void 생성시_생성시각이_기록된다() {
        PurchaseOrderBriefing b = create("발주 브리핑입니다", "{\"panels\":[]}");

        assertThat(b.getBody()).isEqualTo("발주 브리핑입니다");
        assertThat(b.getInputSnapshot()).isEqualTo("{\"panels\":[]}");
        assertThat(b.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(b.getInputTokens()).isEqualTo(400);
        assertThat(b.getOutputTokens()).isEqualTo(120);
        assertThat(b.getCreatedAt()).isNotNull();
    }

    @Test
    void 필수값이_없으면_거부한다() {
        assertThatThrownBy(() -> PurchaseOrderBriefing.create(null, "{\"panels\":[]}", "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PurchaseOrderBriefing.create(" ", "{\"panels\":[]}", "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PurchaseOrderBriefing.create("body", null, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PurchaseOrderBriefing.create("body", " ", "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PurchaseOrderBriefing.create("body", "{\"panels\":[]}", null, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PurchaseOrderBriefing.create("body", "{\"panels\":[]}", " ", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
