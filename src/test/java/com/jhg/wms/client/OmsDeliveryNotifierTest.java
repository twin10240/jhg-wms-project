package com.jhg.wms.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OmsDeliveryNotifier.class)
@TestPropertySource(properties = "oms.base-url=http://oms-test")
class OmsDeliveryNotifierTest {

    @Autowired MockRestServiceServer server;
    @Autowired OmsDeliveryNotifier notifier;

    private static final Instant DELIVERED_AT = Instant.parse("2026-08-27T06:30:00.123456Z");

    @Test
    void send_OMS에_orderId와_배송완료_시각을_POST한다() {
        server.expect(requestTo("http://oms-test/api/delivery-events"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("{\"orderId\":7,\"deliveredAt\":\"2026-08-27T06:30:00.123456Z\"}"))
              .andRespond(withSuccess());

        notifier.send(7L, DELIVERED_AT);
        server.verify();
    }

    @Test
    void send_Authorization_헤더에_콜백_자격증명을_Basic_Auth로_담는다() {
        String expected = "Basic " + HttpHeaders.encodeBasicAuth("wms", "wms", StandardCharsets.UTF_8);
        server.expect(requestTo("http://oms-test/api/delivery-events"))
              .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
              .andRespond(withSuccess());

        notifier.send(7L, DELIVERED_AT);
        server.verify();
    }

    @Test
    void send_OMS가_죽어도_예외를_삼킨다() {
        // 통지는 best-effort — 던지면 afterCommit을 타고 커밋 호출자까지 전파된다.
        server.expect(requestTo("http://oms-test/api/delivery-events"))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatCode(() -> notifier.send(7L, DELIVERED_AT)).doesNotThrowAnyException();
    }

    @Test
    void send_인증실패는_error로_남긴다() {
        Logger logger = (Logger) LoggerFactory.getLogger(OmsDeliveryNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        server.expect(requestTo("http://oms-test/api/delivery-events"))
              .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        notifier.send(7L, DELIVERED_AT);

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("인증 실패"));
    }
}
