package com.jhg.wms.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 재고 보충(채널3) 콜백 — 재고가 늘었다는 사실을 OMS에 통지해 백오더 승격을 트리거한다.
 * 통지는 자연 멱등(사실 전달뿐)이라 중복·재시도 안전. 실패는 삼킨다(best-effort,
 * 유실된 승격은 S4 보상 스윕이 회수 예정).
 */
@Slf4j
@Component
public class OmsReplenishmentNotifier {

    private final RestClient restClient;

    public OmsReplenishmentNotifier(RestClient.Builder builder,
                                    @Value("${oms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    private record ReplenishmentRequest(List<Long> productIds) {}

    /** 현재 트랜잭션 커밋 후에 통지한다(롤백되면 통지 안 나감). */
    public void notifyAfterCommit(Long productId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(productId);
            }
        });
    }

    // afterCommit에서 던진 예외는 커밋 호출자까지 전파되므로 반드시 여기서 삼킨다.
    void send(Long productId) {
        try {
            restClient.post()
                    .uri("/api/replenishments")
                    .body(new ReplenishmentRequest(List.of(productId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("OMS 재고보충 통지 실패(무시 — 백오더 승격 지연): productId={}", productId, e);
        }
    }
}
