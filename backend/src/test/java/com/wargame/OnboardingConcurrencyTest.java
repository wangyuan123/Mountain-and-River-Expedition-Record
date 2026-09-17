package com.wargame;

import com.wargame.service.quest.OnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingConcurrencyTest extends BaseServiceTest {
    @Autowired OnboardingService onboarding;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSupplyClaimsCreditTheMainCityOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(s -> {
            createTestWorld();
            Long id = createTestPlayer("supply-race", 30).getId();
            createBuilding(id, "command", 2); createBuilding(id, "farm", 2); createBuilding(id, "factory", 1);
            onboarding.start(id);
            return id;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Void> claim = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); onboarding.claim(playerId, "base"); return null; };
        try {
            Future<Void> a = executor.submit(claim), b = executor.submit(claim);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            assertEquals(2000, getResources(playerId).getSteel());
            assertEquals(2000, getResources(playerId).getFood());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
