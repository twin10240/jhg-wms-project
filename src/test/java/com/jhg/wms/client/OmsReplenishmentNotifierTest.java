package com.jhg.wms.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OmsReplenishmentNotifier.class)
@TestPropertySource(properties = "oms.base-url=http://oms-test")
class OmsReplenishmentNotifierTest {

    @Autowired MockRestServiceServer server;
    @Autowired OmsReplenishmentNotifier notifier;

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
    void send_OMS가_죽어있어도_예외를_던지지_않는다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withServerError());

        assertThatCode(() -> notifier.send(1L)).doesNotThrowAnyException();
        server.verify();
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
