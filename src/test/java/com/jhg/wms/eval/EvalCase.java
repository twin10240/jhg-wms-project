package com.jhg.wms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 평가셋 한 건.
 *
 * note는 장식이 아니다 — 나중에 점수가 흔들렸을 때 이 케이스가 왜 여기 있는지를
 * 알아야 판단할 수 있다. 라벨만 남으면 반년 뒤 해석이 불가능하다.
 *
 * expectedCategory가 enum이 아니라 String인 이유 — 이 하네스는 반품 사유(ReturnCategory)와
 * 발주 메모(PurchaseOrderMemoCategory) 둘을 잰다. 집계는 라벨을 세기만 하므로 타입을 알 필요가
 * 없고, 알게 하면 하네스가 도메인 enum 하나에 묶인다. 오타는 각 평가셋의 로드 테스트가
 * "모든 라벨이 그 enum의 값인가"로 잡는다 — 형식 오류를 잡을 자리는 여기가 아니라 거기다.
 */
public record EvalCase(String id, String reason, String expectedCategory, String note) {

    public static List<EvalCase> loadAll(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("평가셋을 읽지 못했습니다: " + path, e);
        }
    }
}
