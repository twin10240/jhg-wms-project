package com.jhg.wms.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static com.jhg.wms.support.OrderKeys.keyOf;

@RestClientTest(OmsDeliveryNotifier.class)
@TestPropertySource(properties = {
        "oms.base-url=http://oms-test",
        "OMS_CALLBACK_FAIL_FIRST=true"
})
class OmsDeliveryNotifierFaultTest {

    @Autowired MockRestServiceServer server;
    @Autowired OmsDeliveryNotifier notifier;

    @Test
    void 첫_콜백은_실패하고_두번째_재통지는_전송된다() {
        var requestKey = keyOf(7L);
        var deliveredAt = Instant.parse("2026-08-27T06:30:00.123456Z");

        assertThatCode(() -> notifier.send(requestKey, 7L, deliveredAt)).doesNotThrowAnyException();
        server.expect(requestTo("http://oms-test/api/delivery-events")).andRespond(withSuccess());
        notifier.send(requestKey, 7L, deliveredAt);
        server.verify();
    }
}
