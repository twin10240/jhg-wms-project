package com.jhg.wms.client;

import com.jhg.wms.domain.BriefingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델에게 실제로 무엇이 가는지 고정한다. API 호출 없이 도는 순수 함수라 공짜로 확인된다.
 */
class BriefingPromptRenderTest {

    private BriefingSnapshot.Row row(long id, String name, Double days, Long lastId) {
        return new BriefingSnapshot.Row(id, name, 60, 30L, 2.0, 15, days,
                lastId, lastId == null ? null : LocalDate.of(2026, 9, 1), lastId == null ? null : 50);
    }

    @Test
    void 소진_예상이_null이면_없음으로_쓴다() {
        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(row(1, "볼펜", null, 900L))));

        assertThat(rendered).contains("소진 예상: 없음");
        assertThat(rendered).doesNotContain("소진 예상: 0");
    }

    @Test
    void 직전_발주가_null이면_없음으로_쓴다() {
        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(row(2, "노트", 3.5, null))));

        assertThat(rendered).contains("직전 발주: 없음");
        assertThat(rendered).doesNotContain("null");
    }

    // 일평균은 소수 1자리로 고정한다. 렌더가 3.2를 주면 채점기의 근거 집합도 3.2 기준이 된다 —
    // 여기와 채점기가 어긋나면 정상 인용이 환각으로 잡힌다.
    // 두 차례 평가에서 모델이 이 경과일을 지어냈다 — 이제 표에 직접 준다. 렌더 형식을 고정한다.
    @Test
    void 직전_발주_줄에_경과일을_보여준다() {
        var r = new BriefingSnapshot.Row(1L, "A4용지", 240, 30L, 8.0, 12, 1.5,
                812L, LocalDate.of(2026, 8, 20), 200);

        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(r)));

        assertThat(rendered).contains("직전 발주: #812 2026-08-20 200개 (21일 전)");
    }

    @Test
    void 일평균은_소수_한_자리로_쓴다() {
        var r = new BriefingSnapshot.Row(3L, "테이프", 97, 30L, 3.2333, 10, 3.09, null, null, null);

        String rendered = ClaudePurchaseOrderBriefingGenerator.renderInput(
                new BriefingSnapshot(LocalDate.of(2026, 9, 10), List.of(r)));

        assertThat(rendered).contains("일평균: 3.2");
        assertThat(rendered).doesNotContain("3.2333");
    }
}
