package com.jhg.wms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.ReturnCategory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 평가셋 한 건.
 *
 * note는 장식이 아니다 — 나중에 점수가 흔들렸을 때 이 케이스가 왜 여기 있는지를
 * 알아야 판단할 수 있다. 라벨만 남으면 반년 뒤 해석이 불가능하다.
 */
public record EvalCase(String id, String reason, ReturnCategory expectedCategory, String note) {

    private static final String PATH = "eval/return-reasons.json";

    public static List<EvalCase> loadAll() {
        try (var in = new ClassPathResource(PATH).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("평가셋을 읽지 못했습니다: " + PATH, e);
        }
    }
}
