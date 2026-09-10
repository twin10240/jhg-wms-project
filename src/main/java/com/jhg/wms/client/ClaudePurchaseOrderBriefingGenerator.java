package com.jhg.wms.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.jhg.wms.domain.BriefingSnapshot;
import com.jhg.wms.service.PurchaseOrderBriefingGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 분류 둘과 달리 <b>구조화 출력을 쓰지 않는다</b>. 브리핑은 문단이라 가둘 스키마가 없다.
 *
 * <p>그래서 모델이 숫자를 문장 안에 직접 쓰고, 틀릴 수 있다. 이것은 사고가 아니라
 * 의도된 설계다 — 스펙 "거부한 것" 절 참조. 환각률을 재고 나서 구조를 정한다.
 */
@Slf4j
public class ClaudePurchaseOrderBriefingGenerator implements PurchaseOrderBriefingGenerator {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;

    public ClaudePurchaseOrderBriefingGenerator(AnthropicClient client, String model, long maxTokens) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.systemPrompt = readResource("prompts/purchase-order-briefing.txt");
    }

    @Override
    public Optional<Briefing> generate(BriefingSnapshot snapshot) {
        if (snapshot == null || snapshot.rows().isEmpty()) return Optional.empty();

        try {
            Message message = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .addUserMessage(renderInput(snapshot))
                    .build());

            String body = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining())
                    .trim();

            if (body.isBlank()) return Optional.empty();

            return Optional.of(new Briefing(body, message.model().asString(),
                    (int) message.usage().inputTokens(), (int) message.usage().outputTokens()));
        } catch (Exception e) {
            log.warn("발주 브리핑 생성 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 모델이 읽을 표. 채점기의 근거 집합이 이 값들이므로 <b>여기의 반올림 규칙이 곧 채점 기준</b>이다.
     * 일평균을 소수 1자리로 내면 모델이 "3.2"라고 쓰는 것이 정상 인용이 된다.
     */
    static String renderInput(BriefingSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("기준일: ").append(snapshot.generatedOn()).append("\n\n");
        for (BriefingSnapshot.Row r : snapshot.rows()) {
            sb.append("상품 #").append(r.productId()).append(" ").append(r.productName()).append("\n");
            sb.append("  최근 출고: ").append(r.shippedQty())
              .append("개 (표본 ").append(r.sampleDays()).append("일)\n");
            sb.append("  일평균: ").append(String.format("%.1f", r.dailyAverage())).append("개\n");
            sb.append("  가용: ").append(r.availableQty()).append("개\n");
            sb.append("  소진 예상: ")
              .append(r.daysToStockout() == null ? "없음" : String.format("%.1f일", r.daysToStockout()))
              .append("\n");
            sb.append("  직전 발주: ")
              .append(r.lastOrderId() == null ? "없음"
                      : "#" + r.lastOrderId() + " " + r.lastOrderedOn() + " " + r.lastOrderQty() + "개"
                        + " (" + snapshot.daysSinceLastOrder(r) + "일 전)")
              .append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 리소스를 읽지 못했습니다: " + path, e);
        }
    }
}
