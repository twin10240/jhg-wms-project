package com.jhg.wms.concurrency;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 트랜잭션 경계를 가진 동시성 테스트의 공통 기반.
 *
 * <p>@DataJpaTest는 테스트 메서드 전체를 하나의 트랜잭션으로 감싸고 끝나면 롤백한다.
 * 스레드를 띄워도 그 스레드는 별도 커넥션을 쓰므로 아직 커밋되지 않은 시드 데이터를 보지 못한다 —
 * 경합이 아니라 그냥 실패한다. 그래서 @SpringBootTest(테스트 트랜잭션 없음)를 쓰고,
 * 시드·정리를 TransactionTemplate으로 직접 커밋한다.
 *
 * <p>롤백이 없으므로 뒷정리가 필수다. 정리하지 않으면 캐시된 @DataJpaTest 컨텍스트가
 * 고정으로 쓰는 productId 1·2와 충돌한다. 테스트는 productId·orderId 모두 9000 이상만 쓴다.
 *
 * <p>InitDb 시딩은 테스트에서 꺼져 있으므로(wms.init-db.enabled=false) 목킹하지 않는다.
 */
@SpringBootTest
abstract class ConcurrencySupport {

    /** InitDb 시드(1~20)와 겹치지 않는 테스트 전용 구간. */
    protected static final long PID_BASE = 9000L;
    protected static final long ORDER_BASE = 9000L;

    /** race() 한 판이 이 시간을 넘기면 hang으로 보고 실패시킨다. */
    private static final long RACE_TIMEOUT_SECONDS = 10;

    @Autowired protected InventoryRepository inventoryRepository;
    @Autowired protected InventoryTransactionRepository transactionRepository;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected EntityManager em;

    /** 성공 건수와 실패 원인. 단언은 타이밍이 아니라 이 집계 위에 쓴다. */
    protected record RaceResult(int succeeded, int failed, List<Throwable> errors) {}

    /**
     * threads개의 스레드를 같은 순간에 출발시킨다.
     * task가 true를 반환하면 성공, false를 반환하거나 예외를 던지면 실패로 집계한다.
     * 낙관적 락 충돌(ObjectOptimisticLockingFailureException)도 실패로 잡힌다.
     */
    protected RaceResult race(int threads, IntPredicate task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.test(index) ? null : new IllegalStateException("task returned false");
                } catch (Throwable t) {
                    return t;
                }
            }));
        }

        start.countDown();   // 동시 출발

        int succeeded = 0;
        List<Throwable> errors = new ArrayList<>();
        try {
            for (Future<Throwable> f : futures) {
                Throwable t = f.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (t == null) succeeded++;
                else errors.add(t);
            }
        } catch (Exception e) {
            throw new AssertionError("race()가 " + RACE_TIMEOUT_SECONDS + "초 안에 끝나지 않았다(hang 의심)", e);
        } finally {
            pool.shutdownNow();
        }
        return new RaceResult(succeeded, errors.size(), errors);
    }

    /**
     * 테스트 트랜잭션 밖에서 커밋한다 — 다른 스레드가 볼 수 있어야 경합이 성립한다.
     * OPENING 원장행을 함께 남긴다: 재고 행만 만들면 아래 @AfterEach 불변식(Σdelta == onHand)을
     * 시드 자체가 깨서 모든 시나리오가 무의미하게 붉어진다. 프로덕션 InitDb.seed()와 같은 규약.
     */
    protected void seedInventory(long productId, int onHand) {
        tx.executeWithoutResult(s -> {
            inventoryRepository.save(Inventory.create(productId, "테스트상품 " + productId, onHand));
            transactionRepository.save(InventoryTransaction.of(
                    productId, InventoryTransactionType.OPENING, onHand, 0, onHand, null, null, "test"));
        });
    }

    protected int onHandOf(long productId) {
        return tx.execute(s -> inventoryRepository.findByProductId(productId).orElseThrow().getOnHandQty());
    }

    protected int reservedOf(long productId) {
        return tx.execute(s -> inventoryRepository.findByProductId(productId).orElseThrow().getReservedQty());
    }

    /**
     * 불변식은 별도 테스트가 아니라 후크로 둔다 — 시나리오가 하나 늘 때마다 검증이 따라온다.
     * 별도 테스트로 두면 "그 테스트에서만" 참인 것이 된다.
     * 검증을 먼저 하고 정리한다(정리가 먼저면 검증할 대상이 사라진다).
     */
    @AfterEach
    void 불변식을_확인하고_정리한다() {
        tx.executeWithoutResult(s ->
                inventoryRepository.findAll().forEach(inv -> {
                    int sumDelta = transactionRepository.sumDeltaByProductId(inv.getProductId());
                    assertThat(sumDelta)
                            .as("Σdelta == onHand 위반 (productId=%d)", inv.getProductId())
                            .isEqualTo(inv.getOnHandQty());
                }));
        cleanUpTestRows();
    }

    /** 테스트가 만든 행을 지운다. 9000 이상 구간만 건드려 다른 클래스와 간섭하지 않는다. */
    private void cleanUpTestRows() {
        tx.executeWithoutResult(s -> {
            em.createQuery("DELETE FROM CycleCountItem i").executeUpdate();
            em.createQuery("DELETE FROM CycleCount c").executeUpdate();
            em.createNativeQuery("DELETE FROM reservation_item WHERE reservation_id IN "
                    + "(SELECT reservation_id FROM reservation WHERE order_id >= :base)")
                    .setParameter("base", ORDER_BASE).executeUpdate();
            em.createQuery("DELETE FROM Reservation r WHERE r.orderId >= :base")
                    .setParameter("base", ORDER_BASE).executeUpdate();
            em.createQuery("DELETE FROM InventoryTransaction t WHERE t.productId >= :base")
                    .setParameter("base", PID_BASE).executeUpdate();
            em.createQuery("DELETE FROM Inventory i WHERE i.productId >= :base")
                    .setParameter("base", PID_BASE).executeUpdate();
        });
    }
}
