package com.jhg.wms.client;

import ch.qos.logback.classic.Level;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OmsReplenishmentNotifier.class)
@TestPropertySource(properties = "oms.base-url=http://oms-test")
class OmsReplenishmentNotifierTest {

    @Autowired MockRestServiceServer server;
    @Autowired OmsReplenishmentNotifier notifier;

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(OmsReplenishmentNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void send_OMS에_productIds를_POST한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("{\"productIds\":[1]}"))
              .andRespond(withSuccess());

        notifier.send(1L);
        server.verify();
    }

    @Test
    void send_Authorization_헤더에_콜백_자격증명을_Basic_Auth로_담는다() {
        String expected = "Basic " + HttpHeaders.encodeBasicAuth("wms", "wms", StandardCharsets.UTF_8);
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
              .andRespond(withSuccess());

        notifier.send(1L);
        server.verify();
    }

    @Test
    void send_OMS가_죽어있어도_예외를_던지지_않고_warn으로_기록한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withServerError());
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertThatCode(() -> notifier.send(1L)).doesNotThrowAnyException();

        server.verify();
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void send_OMS가_401을_반환하면_예외를_던지지_않고_error로_기록한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertThatCode(() -> notifier.send(1L)).doesNotThrowAnyException();

        server.verify();
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void send_OMS가_403을_반환하면_예외를_던지지_않고_error로_기록한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withStatus(HttpStatus.FORBIDDEN));
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertThatCode(() -> notifier.send(1L)).doesNotThrowAnyException();

        server.verify();
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void 콜백_자격증명이_공백이면_기동을_실패시킨다() {
        RestClient.Builder builder = RestClient.builder();

        assertThatThrownBy(() -> new OmsReplenishmentNotifier(builder, "http://x", "", "wms"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OmsReplenishmentNotifier(builder, "http://x", "wms", "   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notifyAfterCommit_동기화를_등록하고_커밋_시점에만_전송한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withSuccess());

        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.notifyAfterCommit(1L);
            var syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit(); // 커밋 시점 시뮬레이션 — 이때 비로소 전송
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        server.verify();
    }
}
