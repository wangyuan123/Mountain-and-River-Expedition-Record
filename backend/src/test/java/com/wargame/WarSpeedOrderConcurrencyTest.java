package com.wargame;

import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarSpeedOrderConcurrencyTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @Autowired PlatformTransactionManager transactions;

    @Test void rollbackAndConcurrentConfirmationsConsumeOnlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long attacker = createTestPlayer("war-a-" + suffix, 30).getId();
        Long defender = createTestPlayer("war-d-" + suffix, 30).getId();
        PlayerCity city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defender);
        city.setName("目标城");
        city.setX(45);
        city.setY(88);
        Long cityId = playerCityRepository.saveAndFlush(city).getId();
        items.saveAndFlush(new PlayerItem(null, attacker, "warSpeedOrder", 2, GameClock.now()));
        assertEquals(true, worldService.declareWar(attacker, cityId).get("success"));
        String token = (String) worldService.previewWarOrder(attacker, cityId).get("confirmationToken");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            worldService.accelerateWar(attacker, cityId, token);
            status.setRollbackOnly();
        });
        assertEquals(2, items.findByPlayerIdAndItemKey(attacker, "warSpeedOrder").orElseThrow().getCount());
        assertFalse(playerRepository.findById(attacker).orElseThrow().isWarAccelerated());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> use = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                worldService.accelerateWar(attacker, cityId, token);
                return true;
            } catch (IllegalArgumentException stale) {
                return false;
            }
        };
        try {
            Future<Boolean> first = workers.submit(use);
            Future<Boolean> second = workers.submit(use);
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, items.findByPlayerIdAndItemKey(attacker, "warSpeedOrder").orElseThrow().getCount());
        } finally {
            workers.shutdownNow();
        }
    }
}
