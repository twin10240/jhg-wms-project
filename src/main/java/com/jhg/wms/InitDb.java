package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.InventoryTransaction;
import com.jhg.wms.domain.InventoryTransactionType;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.InventoryTransactionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitDb {

    private final InitService initService;
    // scale 프로파일에서만 존재(RedissonConfig). 단일 인스턴스에선 비어 있어 락 없이 기존 경로로 시딩.
    private final ObjectProvider<RedissonClient> redissonProvider;

    @Value("${INSTANCE_ID:single}")
    private String instanceId;

    @PostConstruct
    public void init() {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            seedIfNeeded();   // 단일 인스턴스 — 경합 없음, 기존과 동일
            return;
        }
        // 다중 인스턴스 동시 기동 시 시딩 경합(product_id UNIQUE 충돌) 방지 — 락 잡은 1개만 시딩.
        RLock lock = redisson.getLock("wms:init-lock");
        lock.lock();
        try {
            seedIfNeeded();
        } finally {
            lock.unlock();
        }
    }

    private void seedIfNeeded() {
        if (initService.alreadySeeded()) {
            initService.backfillNames(); // 이름 컬럼 도입 전 시드된 기존 배포분 보정 — 채워지면 no-op
            initService.migrateLegacy(); // 트랜잭션 원장 도입 전 배포분 보정(type 백필 + OPENING 소급) — 채워지면 no-op
            log.info("[{}] 재고 이미 시드됨 — 시딩 skip", instanceId);
            return;
        }
        initService.seed();
        log.info("[{}] 재고 1~20 시드 완료", instanceId);
    }

    @Component
    @Transactional
    @RequiredArgsConstructor
    static class InitService {
        private final InventoryRepository inventoryRepository;
        private final InventoryTransactionRepository transactionRepository;

        public boolean alreadySeeded() {
            return inventoryRepository.count() > 0;
        }

        // ponytail: 일회성 백필. 모든 행이 이름을 가지면 삭제 가능(그때까진 부팅당 null만 스캔, 20행이라 무해).
        public void backfillNames() {
            inventoryRepository.findAll().forEach(inv -> {
                if (inv.getProductName() == null)
                    inv.setProductName("상품 " + inv.getProductId());
            });
        }

        public void seed() {
            for (int i = 0; i < 20; i++) {
                long productId = i + 1;          // OMS 시드의 상품 id 1~20과 일치
                int onHandQty = 15 * (i + 1);    // OMS initDb와 동일: 15, 30, ..., 300
                String productName = "상품 " + productId; // ponytail: OMS 실제 상품명 확보 시 교체
                inventoryRepository.save(Inventory.create(productId, productName, onHandQty));
                transactionRepository.save(InventoryTransaction.of(
                        productId, InventoryTransactionType.OPENING, onHandQty, 0, onHandQty, null, null));
            }
        }

        /** 기존 배포분 보정(멱등): 구 조정행 type 백필 + 재고별 OPENING 소급.
         *  OPENING delta는 현재 onHand 전체가 아니라 "원장 잔여분"(onHand - 기존 델타합)이어야
         *  Σdelta==onHand 불변식이 유지된다 — 구 조정행이 이미 원장에 있으므로 그만큼을 빼야 이중계상을 피한다. */
        public void migrateLegacy() {
            transactionRepository.assignAdjustTypeToLegacy();
            inventoryRepository.findAll().forEach(inv -> {
                if (!transactionRepository.existsByProductIdAndType(inv.getProductId(), InventoryTransactionType.OPENING)) {
                    int prior = transactionRepository.sumDeltaByProductId(inv.getProductId());
                    int opening = inv.getOnHandQty() - prior;
                    transactionRepository.save(InventoryTransaction.of(
                            inv.getProductId(), InventoryTransactionType.OPENING, opening, 0, opening, null, null));
                }
            });
        }
    }
}
