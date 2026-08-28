package com.jhg.wms.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * 배송 완료 콜백 — 창고가 기록한 배송 완료 사실을 OMS에 통지해 Delivery를 DELIVERED로 올린다.
 * 사실 전달뿐이라 자연 멱등(OMS가 이미 DELIVERED면 no-op). 실패는 삼킨다 — 보상 스윕 대신
 * OMS 관리자 화면의 기존 배송완료 버튼이 복구 경로이고, WMS에서 재클릭하면 통지만 재발송된다.
 */
@Slf4j
@Component
public class OmsDeliveryNotifier {

    private final RestClient restClient;

    public OmsDeliveryNotifier(RestClient.Builder builder,
                               @Value("${oms.base-url}") String baseUrl,
                               @Value("${oms.callback.user}") String callbackUser,
                               @Value("${oms.callback.password}") String callbackPassword) {
        if (callbackUser.isBlank() || callbackPassword.isBlank()) {
            throw new IllegalStateException("oms.callback.user/password must not be blank");
        }
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(callbackUser, callbackPassword))
                .build();
    }

    private record DeliveryEvent(Long orderId, Instant deliveredAt) {}

    /** 현재 트랜잭션 커밋 후에 통지한다(롤백되면 통지 안 나감). */
    public void notifyAfterCommit(Long orderId, Instant deliveredAt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(orderId, deliveredAt);
            }
        });
    }

    // afterCommit에서 던진 예외는 커밋 호출자까지 전파되므로 반드시 여기서 삼킨다.
    void send(Long orderId, Instant deliveredAt) {
        try {
            restClient.post()
                    .uri("/api/delivery-events")
                    .body(new DeliveryEvent(orderId, deliveredAt))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.error("OMS 배송완료 통지 인증 실패(OMS_CALLBACK_USER/PASSWORD 확인 필요): orderId={}", orderId, e);
        } catch (Exception e) {
            log.warn("OMS 배송완료 통지 실패(무시 — OMS 화면에서 수동 배송완료 가능): orderId={}", orderId, e);
        }
    }
}
