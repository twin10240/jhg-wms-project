package com.jhg.wms.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.BriefingJudgeInput;
import com.jhg.wms.domain.BriefingSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 브리핑의 유용성을 체크리스트로 채점한다.
 *
 * <p><b>기계가 잴 수 있는 것은 여기서 묻지 않는다.</b> 숫자 정확성은 {@link GroundingScorer}가
 * 확정적으로 답하고, "가장 급한 상품을 언급했는가"도 스냅샷과 본문 대조로 답할 수 있다.
 * 확정적으로 답할 수 있는 것을 모델에게 물으면 비용을 더 내고 답을 덜 믿게 된다.
 *
 * <p>점수(1~5)가 아니라 예/아니오다. 점수는 회차마다 흔들려 "3.4 → 3.7"이 개선인지 잡음인지
 * 가릴 수 없다. 예/아니오면 {@link EvalAggregator}의 3회 다수결이 그대로 적용된다.
 *
 * <p>judge에게 보여주는 표는 {@link BriefingJudgeInput}을 통해
 * {@code ClaudePurchaseOrderBriefingGenerator.renderInput}을 그대로 부른다 — 생성 모델이 실제로
 * 본 표와 한 글자도 달라선 안 된다. renderInput은 client 패키지 전용이라 eval에서 직접
 * 부를 수 없고, 그렇다고 테스트 편의로 운영 가시성을 넓히지 않는다.
 */
public class BriefingJudge {

    /** 순서 고정 — 리포트의 열 순서가 회차마다 바뀌면 비교가 안 된다. */
    public static final List<String> ITEMS = List.of("REASONED", "NO_INVENTED_FACTS", "ACTIONABLE");

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;
    private final JsonOutputFormat.Schema schema;

    public BriefingJudge(AnthropicClient client, ObjectMapper objectMapper, String model, long maxTokens) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.systemPrompt = readResource("prompts/briefing-judge.txt");
        this.schema = toSchema(objectMapper, readResource("prompts/briefing-judge-schema.json"));
    }

    public record Verdict(Map<String, Boolean> items, String model, int inputTokens, int outputTokens) {}

    public Optional<Verdict> judge(String body, BriefingSnapshot snapshot) {
        try {
            String user = "[표]\n" + BriefingJudgeInput.render(snapshot)
                    + "\n\n[브리핑]\n" + body;

            Message message = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .outputConfig(OutputConfig.builder()
                            .format(JsonOutputFormat.builder().schema(schema).build())
                            .build())
                    .addUserMessage(user)
                    .build());

            String json = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());

            return parse(objectMapper, json).map(items -> new Verdict(items,
                    message.model().asString(),
                    (int) message.usage().inputTokens(),
                    (int) message.usage().outputTokens()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 항목이 하나라도 빠지면 통째로 버린다 — 반만 읽으면 없는 항목이 조용히 통과한다. */
    static Optional<Map<String, Boolean>> parse(ObjectMapper mapper, String json) {
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<>() {});
            Map<String, Boolean> items = new LinkedHashMap<>();
            for (String item : ITEMS) {
                Object v = raw.get(item);
                if (!(v instanceof Boolean b)) return Optional.empty();
                items.put(item, b);
            }
            return Optional.of(items);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String readResource(String path) {
        try (var in = BriefingJudge.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("리소스 없음: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonOutputFormat.Schema toSchema(ObjectMapper mapper, String json) {
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<>() {});
            JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
            map.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
            return b.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
