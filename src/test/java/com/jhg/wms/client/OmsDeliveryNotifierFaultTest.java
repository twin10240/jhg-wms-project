package com.jhg.wms.client;

import com.jhg.wms.client.OmsDeliveryNotifier.DeliveryStatusEvent;
import com.jhg.wms.domain.DeliveryFailureCode;
import com.jhg.wms.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.times;
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
    void 첫_콜백만_실패하고_같은_버튼_재통지와_이후_상태는_전송된다() {
        var failed = new DeliveryStatusEvent(keyOf(7L), 7L, DeliveryStatus.DELIVERY_FAILED, 2,
                Instant.parse("2026-10-19T03:00:00.123456Z"), DeliveryFailureCode.ADDRESS_ISSUE);
        var redelivered = new DeliveryStatusEvent(keyOf(7L), 7L, DeliveryStatus.SHIPPED, 3,
                Instant.parse("2026-10-19T04:00:00.123456Z"), null);

        assertThatCode(() -> notifier.send(failed)).doesNotThrowAnyException();
        // 주입은 프로세스당 한 번이다 — 상태·주문마다 다시 켜지면 모든 첫 통지가 사라진다.
        server.expect(times(2), requestTo("http://oms-test/api/delivery-status-events")).andRespond(withSuccess());
        notifier.send(failed);
        notifier.send(redelivered);
        server.verify();
    }
}
