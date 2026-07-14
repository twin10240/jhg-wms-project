package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class InitDb {

    private final InitService initService;

    @PostConstruct
    public void init() {
        if (initService.alreadySeeded()) {
            initService.backfillNames(); // 이름 컬럼 도입 전 시드된 기존 배포분 보정 — 채워지면 no-op
            return;
        }
        initService.seed();
    }

    @Component
    @Transactional
    @RequiredArgsConstructor
    static class InitService {
        private final InventoryRepository inventoryRepository;

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
            }
        }
    }
}
