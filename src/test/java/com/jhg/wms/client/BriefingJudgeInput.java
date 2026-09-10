package com.jhg.wms.client;

import com.jhg.wms.domain.BriefingSnapshot;

/**
 * {@code com.jhg.wms.eval.BriefingJudge}가 {@link ClaudePurchaseOrderBriefingGenerator#renderInput}을
 * 그대로 쓰게 해 주는 다리.
 *
 * <p>renderInput은 패키지 전용이고 judge는 eval 패키지에 산다. 운영 가시성을 넓혀 public으로
 * 만들면 테스트 편의를 위해 운영 API 표면을 넓히는 것이 되므로 하지 않는다 — 대신 같은
 * 패키지(client)의 테스트 클래스에서만 그 메서드를 노출한다. {@code BriefingPromptRenderTest}가
 * 이미 같은 방식으로 이 메서드에 접근한다.
 *
 * <p>표를 새로 만들지 않고 renderInput을 그대로 부르는 이유 — judge가 채점 근거로 보는 표는
 * 모델이 실제로 본 표와 한 글자도 달라선 안 된다. 복제하면 {@code GroundingScorer}가 세 번
 * 겪은 반올림 표면형 드리프트를 또 만든다.
 */
public final class BriefingJudgeInput {

    private BriefingJudgeInput() {}

    public static String render(BriefingSnapshot snapshot) {
        return ClaudePurchaseOrderBriefingGenerator.renderInput(snapshot);
    }
}
