package com.jhg.wms.client;

import com.jhg.wms.domain.DeliveryFailureCode;
import com.jhg.wms.domain.DeliveryStatus;
import com.jhg.wms.domain.Reservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 배송 상태 콜백 — 창고가 확정한 실패·재배송·완료를 OMS에 버전과 함께 통지한다.
 * OMS가 버전으로 순서를 판단하므로(낮은 버전 no-op) 전달 순서와 중복에 의존하지 않는다. 실패는 삼킨다 —
 * 보상 스윕·재전송 큐 대신 WMS 화면의 같은 버튼(재통지)과 OMS의 송장 조회 동기화가 복구 경로다.
 * 첫 출고는 여기로 보내지 않는다 — OMS는 출고 응답으로 이미 안다.
 */
@Slf4j
@Component
public class OmsDeliveryNotifier {

    private final RestClient restClient;
    private final AtomicBoolean failFirstCallback;

    public OmsDeliveryNotifier(RestClient.Builder builder,
                               @Value("${oms.base-url}") String baseUrl,
                               @Value("${oms.callback.user}") String callbackUser,
                               @Value("${oms.callback.password}") String callbackPassword,
                               @Value("${OMS_CALLBACK_FAIL_FIRST:false}") boolean failFirstCallback) {
        if (callbackUser.isBlank() || callbackPassword.isBlank()) {
            throw new IllegalStateException("oms.callback.user/password must not be blank");
        }
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(callbackUser, callbackPassword))
                .build();
        this.failFirstCallback = new AtomicBoolean(failFirstCallback);
    }

    /**
     * 콜백 본문. requestKey가 주문을 특정하는 키다. orderId는 OMS 로그·화면용이며, OMS DB 초기화로
     * 재사용될 수 있으므로 대조 기준으로 쓰면 안 된다. failureCode는 실패가 아니면 null로 나간다.
     */
    public record DeliveryStatusEvent(UUID requestKey, Long orderId, DeliveryStatus status,
                                      long deliveryVersion, Instant occurredAt, DeliveryFailureCode failureCode) {

        /** 락을 쥔 트랜잭션 안에서 부른다 — 커밋 뒤에 엔티티를 다시 읽으면 다른 요청의 버전이 섞일 수 있다. */
        public static DeliveryStatusEvent snapshotOf(Reservation r) {
            return new DeliveryStatusEvent(r.getRequestKey(), r.getOrderId(), r.getDeliveryStatus(),
                    r.getDeliveryVersion(), r.getDeliveryStatusAt(), r.getDeliveryFailureCode());
        }
    }

    /** 현재 트랜잭션 커밋 후에 통지한다(롤백되면 통지 안 나감). 네트워크 호출 중에는 행 잠금이 이미 풀려 있다. */
    public void notifyAfterCommit(DeliveryStatusEvent event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(event);
            }
        });
    }

    // afterCommit에서 던진 예외는 커밋 호출자까지 전파되므로 반드시 여기서 삼킨다.
    void send(DeliveryStatusEvent event) {
        try {
            if (failFirstCallback.compareAndSet(true, false)) {
                throw new IllegalStateException("첫 OMS 배송 콜백 장애를 주입했습니다.");
            }
            restClient.post()
                    .uri("/api/delivery-status-events")
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.error("OMS 배송 상태 통지 인증 실패(OMS_CALLBACK_USER/PASSWORD 확인 필요): requestKey={}, orderId={}, status={}, version={}",
                    event.requestKey(), event.orderId(), event.status(), event.deliveryVersion(), e);
        } catch (HttpClientErrorException e) {
            // 400·409는 재전송으로 풀리지 않는다 — 형식 오류이거나 OMS가 이 버전을 받을 수 없는 상태다.
            // 저장된 원본을 고쳐 다시 보내지 않고, OMS 관리자 송장 조회 동기화로 맞춘다.
            log.error("OMS 배송 상태 통지 거부({}): requestKey={}, orderId={}, status={}, version={} — OMS 송장 조회 동기화로 복구",
                    e.getStatusCode().value(), event.requestKey(), event.orderId(), event.status(), event.deliveryVersion(), e);
        } catch (Exception e) {
            log.warn("OMS 배송 상태 통지 실패(무시 — WMS 같은 버튼 재통지 또는 OMS 송장 조회 동기화로 복구): requestKey={}, orderId={}, status={}, version={}",
                    event.requestKey(), event.orderId(), event.status(), event.deliveryVersion(), e);
        }
    }
}
