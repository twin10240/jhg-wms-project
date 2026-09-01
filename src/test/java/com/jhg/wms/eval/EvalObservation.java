package com.jhg.wms.eval;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

/**
 * 한 번의 분류 관측.
 *
 * category가 null이면 분류 실패다(classify()가 empty를 반환). 실패를 별도 타입으로
 * 나누지 않는 이유는, 집계가 "몇 번 실패했나"만 알면 되고 원인은 로그의 몫이기 때문이다.
 *
 * model은 응답이 돌려준 확정 스냅샷이다(claude-haiku-4-5-20251001). 요청에 쓴 별칭이
 * 아니라 이 값을 리포트에 적어야 나중에 어느 버전에서 잰 점수인지 알 수 있다.
 */
public record EvalObservation(String caseId,
                              ReturnCategory category,
                              Confidence confidence,
                              RmaDisposition disposition,
                              int inputTokens,
                              int outputTokens,
                              String model) {

    public static EvalObservation failed(String caseId) {
        return new EvalObservation(caseId, null, null, null, 0, 0, null);
    }

    public boolean succeeded() {
        return category != null;
    }
}
