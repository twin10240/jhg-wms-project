package com.jhg.wms.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jhg.wms.client.OmsDeliveryNotifier.DeliveryStatusEvent;
import com.jhg.wms.domain.DeliveryFailureCode;
import com.jhg.wms.domain.DeliveryStatus;
import com.jhg.wms.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.jhg.wms.support.OrderKeys.keyOf;

@RestClientTest(OmsDeliveryNotifier.class)
@TestPropertySource(properties = "oms.base-url=http://oms-test")
class OmsDeliveryNotifierTest {

    private static final String URL = "http://oms-test/api/delivery-status-events";
    private static final Instant AT = Instant.parse("2026-10-19T03:00:00.123456Z");

    @Autowired MockRestServiceServer server;
    @Autowired OmsDeliveryNotifier notifier;

    private static DeliveryStatusEvent failed() {
        return new DeliveryStatusEvent(keyOf(7L), 7L, DeliveryStatus.DELIVERY_FAILED, 2, AT,
                DeliveryFailureCode.RECIPIENT_UNAVAILABLE);
    }

    @Test
    void send_실패는_버전_시각_사유를_마이크로초_그대로_POST한다() {
        server.expect(requestTo(URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
              .andExpect(content().json("""
                      {"requestKey":"00000000-0000-0000-0000-000000000007","orderId":7,
                       "status":"DELIVERY_FAILED","deliveryVersion":2,
                       "occurredAt":"2026-10-19T03:00:00.123456Z","failureCode":"RECIPIENT_UNAVAILABLE"}""", true))
              .andRespond(withSuccess());

        notifier.send(failed());
        server.verify();
    }

    @Test
    void send_재배송과_완료는_failureCode를_null로_싣는다() {
        server.expect(requestTo(URL))
              .andExpect(content().json("""
                      {"status":"SHIPPED","deliveryVersion":3,"failureCode":null}"""))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("\"failureCode\":null")))
              .andRespond(withSuccess());
        server.expect(requestTo(URL))
              .andExpect(content().json("""
                      {"status":"DELIVERED","deliveryVersion":4,"occurredAt":"2026-10-19T05:00:00.123456Z","failureCode":null}"""))
              .andRespond(withSuccess());

        notifier.send(new DeliveryStatusEvent(keyOf(7L), 7L, DeliveryStatus.SHIPPED, 3,
                Instant.parse("2026-10-19T04:00:00.123456Z"), null));
        notifier.send(new DeliveryStatusEvent(keyOf(7L), 7L, DeliveryStatus.DELIVERED, 4,
                Instant.parse("2026-10-19T05:00:00.123456Z"), null));
        server.verify();
    }

    @Test
    void send_이전된_완료행_재통지는_기존_deliveredAt과_v2를_그대로_보낸다() {
        // 이전 SQL이 만든 행(DELIVERED v2, 상태 시각 = 기존 deliveredAt)을 엔티티로 재현한다.
        Instant legacyDeliveredAt = Instant.parse("2026-08-28T01:00:00.123456Z");
        Reservation migrated = Reservation.reserve(keyOf(7L), 7L, Map.of(1L, 1));
        org.springframework.test.util.ReflectionTestUtils.setField(migrated, "deliveryStatus", DeliveryStatus.DELIVERED);
        org.springframework.test.util.ReflectionTestUtils.setField(migrated, "deliveryVersion", 2L);
        org.springframework.test.util.ReflectionTestUtils.setField(migrated, "deliveryStatusAt", legacyDeliveredAt);
        org.springframework.test.util.ReflectionTestUtils.setField(migrated, "deliveredAt", legacyDeliveredAt);

        server.expect(requestTo(URL))
              .andExpect(content().json("""
                      {"status":"DELIVERED","deliveryVersion":2,"occurredAt":"2026-08-28T01:00:00.123456Z","failureCode":null}"""))
              .andRespond(withSuccess());

        notifier.send(DeliveryStatusEvent.snapshotOf(migrated));
        server.verify();
    }

    @Test
    void send_Authorization_헤더에_콜백_자격증명을_Basic_Auth로_담는다() {
        String expected = "Basic " + HttpHeaders.encodeBasicAuth("wms", "wms", StandardCharsets.UTF_8);
        server.expect(requestTo(URL))
              .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
              .andRespond(withSuccess());

        notifier.send(failed());
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "CONFLICT", "INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void send_OMS가_거부하거나_죽어도_예외를_삼킨다(HttpStatus status) {
        // 통지는 best-effort — 던지면 afterCommit을 타고 커밋 호출자까지 전파된다.
        server.expect(requestTo(URL)).andRespond(withStatus(status));

        assertThatCode(() -> notifier.send(failed())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void send_타임아웃도_예외를_삼킨다() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatCode(() -> notifier.send(failed())).doesNotThrowAnyException();
    }

    @Test
    void send_인증실패는_error로_남긴다() {
        Logger logger = (Logger) LoggerFactory.getLogger(OmsDeliveryNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        notifier.send(failed());

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("인증 실패"));
    }
}
