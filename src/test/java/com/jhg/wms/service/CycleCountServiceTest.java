package com.jhg.wms.service;

import com.jhg.wms.client.OmsReplenishmentNotifier;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.domain.CycleCountStatus;
import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.CycleCountRepository;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest
class CycleCountServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired CycleCountRepository cycleCountRepo;
    @Autowired jakarta.persistence.EntityManager em;

    InventoryService inventoryService;
    CycleCountService service;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, txnRepo,
                mock(OmsReplenishmentNotifier.class), () -> "manager");
        service = new CycleCountService(cycleCountRepo, inventoryRepo, inventoryService, () -> "operator");
    }

    private void seed(long pid, int qty) {
        inventoryRepo.save(Inventory.create(pid, qty));
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    @Test
    void 세션을_열면_대상의_장부수량이_스냅샷으로_담긴다() {
        seed(1L, 15);
        seed(2L, 30);

        CycleCount c = service.open(List.of(1L, 2L), "8월 순환 실사");
        flush();

        CycleCount found = service.findById(c.getId());
        assertThat(found.getStatus()).isEqualTo(CycleCountStatus.OPEN);
        assertThat(found.getItems()).extracting("productId", "bookQtyAtOpen")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, 15),
                        org.assertj.core.groups.Tuple.tuple(2L, 30));
    }

    @Test
    void 대상이_없으면_거부한다() {
        assertThatThrownBy(() -> service.open(List.of(), "빈 실사"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대상");
    }

    @Test
    void 재고행이_없는_상품은_거부한다() {
        seed(1L, 15);

        assertThatThrownBy(() -> service.open(List.of(1L, 99L), "없는 상품"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // 나중 세션의 실물 수량은 이미 낡은 값이라, 겹치면 "센 시점"과 "적용 시점"이 어긋난 조정이 남는다.
    @Test
    void 열린_세션에_있는_상품은_새_세션에_담을_수_없다() {
        seed(1L, 15);
        seed(2L, 30);
        service.open(List.of(1L), "먼저 연 세션");
        flush();

        assertThatThrownBy(() -> service.open(List.of(1L, 2L), "겹치는 세션"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행 중인 실사");
    }

    // findOpenProductIds()는 OPEN뿐 아니라 SUBMITTED도 잡아야 한다 — 승인 대기 중인 세션의
    // 실물 수량은 아직 장부에 반영되지 않았으니, 그 상품을 다른 세션이 또 세면 두 세션의 조정이 충돌한다.
    @Test
    void 승인대기_세션에_있는_상품도_새_세션에_담을_수_없다() {
        seed(1L, 15);
        seed(2L, 30);
        CycleCount first = service.open(List.of(1L), "먼저 연 세션");
        flush();
        service.saveCounts(first.getId(), Map.of(itemId(first, 1L), 15));
        service.submit(first.getId());
        flush();

        assertThatThrownBy(() -> service.open(List.of(1L, 2L), "겹치는 세션"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행 중인 실사");
    }

    @Test
    void 종결된_세션의_상품은_다시_담을_수_있다() {
        seed(1L, 15);
        CycleCount first = service.open(List.of(1L), "첫 세션");
        flush();
        service.saveCounts(first.getId(), Map.of(itemId(first, 1L), 15));
        service.submit(first.getId());
        flush();
        service.findById(first.getId()).reject("manager", "재실사");
        flush();

        CycleCount second = service.open(List.of(1L), "두 번째 세션");

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void 미입력_품목이_있으면_제출이_거부된다() {
        seed(1L, 15);
        seed(2L, 30);
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));

        assertThatThrownBy(() -> service.submit(c.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.OPEN);
    }

    @Test
    void 전_품목_입력_후_제출하면_승인대기가_된다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));

        service.submit(c.getId());
        flush();

        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
    }

    /** 화면은 itemId로 값을 보내므로 테스트도 productId → itemId로 변환해 쓴다. */
    private Long itemId(CycleCount session, long productId) {
        return service.findById(session.getId()).getItems().stream()
                .filter(i -> i.getProductId() == productId)
                .findFirst().orElseThrow().getId();
    }
}
