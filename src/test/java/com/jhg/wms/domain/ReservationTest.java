package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.jhg.wms.support.OrderKeys.keyOf;

class ReservationTest {

    @Test
    void reserve_생성시_RESERVED_상태고_수량_원장을_보관한다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3, 20L, 5));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getOrderId()).isEqualTo(1L);
        assertThat(r.getQtyByProductId()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 3, 20L, 5));
    }

    @Test
    void reserve_생성_시각을_담는다() {
        // 이 값이 없으면 예약이 재고를 얼마나 붙들고 있었는지 영영 잴 수 없다.
        // 예약 생성은 onHand를 바꾸지 않아 원장에도 남지 않으므로 우회로가 없다.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(1L, 2));

        assertThat(r.getCreatedAt()).isNotNull();
        assertThat(r.getCreatedAt()).isBetween(before, Instant.now());
        // 마이크로초로 잘려 있어야 저장 직후 값과 재조회 값이 같다(issuedAt과 같은 이유).
        assertThat(r.getCreatedAt().getNano() % 1000).isZero();
    }

    @Test
    void release_해제_시각을_담고_출고는_담지_않는다() {
        Reservation released = Reservation.reserve(keyOf(1L), 1L, Map.of(1L, 2));
        Reservation shipped = Reservation.reserve(keyOf(2L), 2L, Map.of(1L, 2));

        assertThat(released.getReleasedAt()).isNull();

        released.release();
        shipped.ship();

        assertThat(released.getReleasedAt()).isNotNull();
        // 출고 시각은 issuedAt이 갖는다 — 해제 시각 자리에 출고를 적으면 두 사실이 섞인다.
        assertThat(shipped.getReleasedAt()).isNull();
    }

    @Test
    void ship_상태가_SHIPPED로_전이된다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void release_상태가_RELEASED로_전이된다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.release();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void deliver_배송완료_시각을_마이크로초로_잘라_담는다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        r.issueShipment(Instant.parse("2026-08-27T01:00:00Z"));
        // Linux의 Instant.now()는 나노초까지 준다 — delivered_at은 timestamp(6)이라 DB 왕복에서 잘린다.
        r.deliver(Instant.parse("2026-08-27T06:30:00.123456789Z"));
        assertThat(r.getDeliveredAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
    }

    @Test
    void deliver_이전에는_배송완료_시각이_비어있다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        assertThat(r.getDeliveredAt()).isNull();
    }

    @Test
    void issueShipment_송장번호는_MOCK과_주문번호와_UTC_시각과_requestKey로_만든다() {
        Reservation r = Reservation.reserve(keyOf(202L), 202L, Map.of(3L, 1));

        // 2026-08-27T06:30:00Z — KST로는 15:30이다. 로컬 시각이 새어 들어오면 이 단언이 깨진다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00Z"));

        // 끝의 8자는 requestKey 앞자리 — 재사용된 orderId가 같은 초에 출고돼도 번호가 갈린다.
        assertThat(r.getTrackingNumber()).isEqualTo("MOCK-202-20260827063000-00000000");
        assertThat(r.getCarrierCode()).isEqualTo("MOCK");
        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00Z"));
    }

    @Test
    void issueShipment_issuedAt은_마이크로초로_잘려_저장된다() {
        Reservation r = Reservation.reserve(keyOf(203L), 203L, Map.of(3L, 1));

        // issued_at 컬럼은 timestamp(6) — 마이크로초까지만 저장된다. Linux(CI)의 Instant.now()는
        // 나노초 정밀도를 갖지만 macOS는 우연히 마이크로초 배수만 나와 이 차이가 로컬에서는 재현되지 않는다.
        r.issueShipment(Instant.parse("2026-08-27T06:30:00.123456789Z"));

        assertThat(r.getIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
    }

    @Test
    void 예약_직후에는_송장이_없다() {
        Reservation r = Reservation.reserve(keyOf(202L), 202L, Map.of(3L, 1));

        assertThat(r.getTrackingNumber()).isNull();
        assertThat(r.getCarrierCode()).isNull();
        assertThat(r.getIssuedAt()).isNull();
    }

    // ── 배송 상태 전이 ─────────────────────────────────────────

    private static final Instant ISSUED = Instant.parse("2026-10-19T02:00:00.123456Z");

    private static Reservation issued() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();
        r.issueShipment(ISSUED);
        return r;
    }

    @Test
    void 송장_발급_전에는_배송_상태가_없고_버전은_0이다() {
        Reservation r = Reservation.reserve(keyOf(1L), 1L, Map.of(10L, 3));
        r.ship();   // ship()만으로는 v1이 아니다 — 송장이 확정돼야 배송이 시작된다

        assertThat(r.getDeliveryStatus()).isNull();
        assertThat(r.getDeliveryVersion()).isZero();
        assertThatThrownBy(() -> r.deliver(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.recordDeliveryFailure(DeliveryFailureCode.ADDRESS_ISSUE, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.startRedelivery(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 송장_발급이_SHIPPED_v1과_상태시각_issuedAt을_정한다() {
        Reservation r = issued();

        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(r.getDeliveryVersion()).isEqualTo(1);
        assertThat(r.getDeliveryStatusAt()).isEqualTo(ISSUED);
        assertThat(r.getDeliveryFailureCode()).isNull();
    }

    @Test
    void 실패_재배송_완료는_한_칸씩_오르고_현재_실패_코드만_남긴다() {
        Reservation r = issued();

        assertThat(r.recordDeliveryFailure(DeliveryFailureCode.RECIPIENT_UNAVAILABLE,
                Instant.parse("2026-10-19T03:00:00.123456789Z"))).isTrue();
        assertThat(r.getDeliveryVersion()).isEqualTo(2);
        assertThat(r.getDeliveryStatusAt()).isEqualTo(Instant.parse("2026-10-19T03:00:00.123456Z"));   // 마이크로초 절삭
        assertThat(r.getDeliveryFailureCode()).isEqualTo(DeliveryFailureCode.RECIPIENT_UNAVAILABLE);

        assertThat(r.startRedelivery(Instant.parse("2026-10-19T04:00:00Z"))).isTrue();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(r.getDeliveryVersion()).isEqualTo(3);
        assertThat(r.getDeliveryFailureCode()).isNull();

        assertThat(r.deliver(Instant.parse("2026-10-19T05:00:00Z"))).isTrue();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(r.getDeliveryVersion()).isEqualTo(4);
        assertThat(r.getDeliveredAt()).isEqualTo(r.getDeliveryStatusAt());
        // 송장·발급 시각은 배송 시도가 바뀌어도 그대로다.
        assertThat(r.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void 같은_액션_재요청은_false이고_버전_시각을_바꾸지_않는다() {
        Reservation r = issued();
        r.recordDeliveryFailure(DeliveryFailureCode.ADDRESS_ISSUE, Instant.parse("2026-10-19T03:00:00Z"));

        assertThat(r.recordDeliveryFailure(DeliveryFailureCode.ADDRESS_ISSUE, Instant.parse("2026-10-19T09:00:00Z"))).isFalse();
        assertThat(r.getDeliveryVersion()).isEqualTo(2);
        assertThat(r.getDeliveryStatusAt()).isEqualTo(Instant.parse("2026-10-19T03:00:00Z"));

        r.startRedelivery(Instant.parse("2026-10-19T04:00:00Z"));
        assertThat(r.startRedelivery(Instant.parse("2026-10-19T09:00:00Z"))).isFalse();
        assertThat(r.getDeliveryVersion()).isEqualTo(3);

        r.deliver(Instant.parse("2026-10-19T05:00:00Z"));
        assertThat(r.deliver(Instant.parse("2026-10-19T09:00:00Z"))).isFalse();
        assertThat(r.getDeliveryVersion()).isEqualTo(4);
        assertThat(r.getDeliveredAt()).isEqualTo(Instant.parse("2026-10-19T05:00:00Z"));
    }

    @Test
    void 전이표에_없는_요청은_거부하고_아무것도_바꾸지_않는다() {
        Reservation v1 = issued();
        assertThatThrownBy(() -> v1.startRedelivery(Instant.now()))   // 실패 기록 없는 재배송
                .isInstanceOf(IllegalStateException.class);
        assertThat(v1.getDeliveryVersion()).isEqualTo(1);

        Reservation failed = issued();
        failed.recordDeliveryFailure(DeliveryFailureCode.RECIPIENT_UNAVAILABLE, Instant.now());
        assertThatThrownBy(() -> failed.recordDeliveryFailure(DeliveryFailureCode.ADDRESS_ISSUE, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.deliver(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(failed.getDeliveryVersion()).isEqualTo(2);
        assertThat(failed.getDeliveryFailureCode()).isEqualTo(DeliveryFailureCode.RECIPIENT_UNAVAILABLE);
        assertThat(failed.getDeliveredAt()).isNull();

        Reservation delivered = issued();
        delivered.deliver(Instant.now());
        assertThatThrownBy(() -> delivered.recordDeliveryFailure(DeliveryFailureCode.ADDRESS_ISSUE, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivered.startRedelivery(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(delivered.getDeliveryVersion()).isEqualTo(2);
    }

    @Test
    void 실패_사유가_없으면_거부한다() {
        Reservation r = issued();
        assertThatThrownBy(() -> r.recordDeliveryFailure(null, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.getDeliveryVersion()).isEqualTo(1);
    }

    @Test
    void 실패_사유_입력은_두_코드만_받는다() {
        assertThat(DeliveryFailureCode.parse("ADDRESS_ISSUE")).isEqualTo(DeliveryFailureCode.ADDRESS_ISSUE);
        for (String bad : new String[]{null, "", "  ", "LOST", "address_issue"})
            assertThatThrownBy(() -> DeliveryFailureCode.parse(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
