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
        if (initService.alreadySeeded()) return;
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

        public void seed() {
            for (int i = 0; i < 20; i++) {
                long productId = i + 1;          // OMS 시드의 상품 id 1~20과 일치
                int onHandQty = 15 * (i + 1);    // OMS initDb와 동일: 15, 30, ..., 300
                inventoryRepository.save(Inventory.create(productId, onHandQty));
            }
        }
    }
}
