package com.jhg.wms.client;

import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.web.RmaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class OmsReturnStatusNotifier {

    private final RestClient restClient;

    public OmsReturnStatusNotifier(RestClient.Builder builder,
                                   @Value("${oms.base-url}") String baseUrl,
                                   @Value("${oms.callback.user}") String callbackUser,
                                   @Value("${oms.callback.password}") String callbackPassword) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(callbackUser, callbackPassword))
                .build();
    }

    public void notifyAfterCommit(RmaReturn rma) {
        RmaResponse payload = RmaResponse.from(rma);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(payload);
            }
        });
    }

    void send(RmaResponse payload) {
        try {
            restClient.post()
                    .uri("/api/return-status-events")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.error("OMS 반품 결과 통지 인증 실패: rmaId={}", payload.rmaId(), e);
        } catch (Exception e) {
            log.warn("OMS 반품 결과 통지 실패(무시): rmaId={}", payload.rmaId(), e);
        }
    }
}
