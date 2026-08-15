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
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DataJpaTest
class CycleCountServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired InventoryTransactionRepository txnRepo;
    @Autowired CycleCountRepository cycleCountRepo;
    @Autowired jakarta.persistence.EntityManager em;

    InventoryService inventoryService;
    CycleCountService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, txnRepo,
                notifier, () -> "manager");
        service = new CycleCountService(cycleCountRepo, inventoryRepo, txnRepo, inventoryService, () -> "operator");
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

    /** 실사는 세는 동안 재고가 움직인다. 차이는 승인 시점 장부로 다시 계산해야 원장 불변식이 유지된다. */
    @Test
    void 승인은_실사중_이동을_반영해_승인시점_장부로_차이를_계산한다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14));   // 실물 14
        service.submit(c.getId());
        flush();
        inventoryService.applyDelta(1L, -2, com.jhg.wms.domain.InventoryTransactionType.SHIP,
                "ORDER#99", null);                                   // 실사 중 출고 → 장부 13
        flush();

        service.approve(c.getId());
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(14);
        assertThat(txnRepo.findAll())
                .filteredOn(t -> t.getType() == com.jhg.wms.domain.InventoryTransactionType.COUNT)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getDelta()).isEqualTo(1);       // 14 − 13
                    assertThat(t.getBeforeQty()).isEqualTo(13);
                    assertThat(t.getAfterQty()).isEqualTo(14);
                    assertThat(t.getReference()).isEqualTo("COUNT#" + c.getId());
                    assertThat(t.getActor()).isEqualTo("manager");
                });
        verify(notifier).notifyAfterCommit(1L);   // 실사로 재고가 늘었으니 OMS 백오더 승격 통지가 따라온다
    }

    @Test
    void 차이가_없는_품목은_원장에_남기지_않는다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 15));
        service.submit(c.getId());
        flush();

        service.approve(c.getId());
        flush();

        assertThat(txnRepo.findAll())
                .filteredOn(t -> t.getType() == com.jhg.wms.domain.InventoryTransactionType.COUNT)
                .isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.APPROVED);
    }

    @Test
    void 반려하면_장부와_원장이_그대로다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 3));
        service.submit(c.getId());
        flush();

        service.reject(c.getId(), "계수 오류");
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
        assertThat(txnRepo.findAll()).isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.REJECTED);
    }

    // 반영 전에 전 품목의 반영 가능 여부를 검증하므로, 한 품목이라도 반영 불가면 애초에
    // 아무 품목도 applyDelta를 타지 않는다 — 부분 반영이 없다(2패스: 전량 검증 후 전량 반영).
    // ponytail: 이 테스트는 프로덕션 트랜잭션 롤백 자체를 검증하지 않는다. 이 하네스는
    // CycleCountService/InventoryService를 new로 직접 생성해 Spring AOP 프록시가 없으므로
    // @Transactional 롤백이 애초에 작동하지 않는 환경이다 — 여기서 통과해도 "롤백이 된다"는
    // 증거는 아니다. 프로덕션 롤백 경로는 별도 커버리지가 없다.
    @Test
    void 한_품목이라도_반영_불가면_아무것도_반영하지_않는다() {
        seed(1L, 15);
        seed(2L, 10);
        inventoryService.reserveAll(77L, Map.of(2L, 8));   // 상품2는 8개가 예약된 상태
        flush();
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 16, itemId(c, 2L), 3));  // 상품2는 예약 8 미만
        service.submit(c.getId());
        flush();

        assertThatThrownBy(() -> service.approve(c.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        flush();

        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
        assertThat(txnRepo.findAll()).isEmpty();
        assertThat(service.findById(c.getId()).getStatus()).isEqualTo(CycleCountStatus.SUBMITTED);
    }

    @Test
    void 승인대기가_아니면_승인할_수_없다() {
        seed(1L, 15);
        CycleCount c = service.open(List.of(1L), "실사");
        flush();

        assertThatThrownBy(() -> service.approve(c.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 반영된_차이는_원장에서_읽는다() {
        seed(1L, 15);
        seed(2L, 30);
        CycleCount c = service.open(List.of(1L, 2L), "실사");
        flush();
        service.saveCounts(c.getId(), Map.of(itemId(c, 1L), 14, itemId(c, 2L), 30));
        service.submit(c.getId());
        flush();
        service.approve(c.getId());
        flush();

        assertThat(service.appliedDeltas(c.getId())).containsExactly(entry(1L, -1));  // 상품2는 일치라 없음
    }

    /** 화면은 itemId로 값을 보내므로 테스트도 productId → itemId로 변환해 쓴다. */
    private Long itemId(CycleCount session, long productId) {
        return service.findById(session.getId()).getItems().stream()
                .filter(i -> i.getProductId() == productId)
                .findFirst().orElseThrow().getId();
    }
}
