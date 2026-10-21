package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.service.InterceptionPlanner;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InterceptionAmbushConcurrencyTest extends BaseServiceTest {
    @Autowired PlatformTransactionManager transactions;
    record Fixture(Long attacker, Long defender, Long enemy, long now) {}

    @Test
    void simultaneousAttackerAndDefenderTicksCommitOneBattleAndOneAmbush() throws Exception {
        Fixture f = new TransactionTemplate(transactions).execute(status -> {
            Long attacker = createTestPlayer("ambush-a-" + UUID.randomUUID(), 30).getId();
            Long defender = createTestPlayer("ambush-d-" + UUID.randomUUID(), 30).getId();
            PlayerCity city = new PlayerCity(); city.setWorldId(createTestWorld().getId());
            city.setOwnerId(defender); city.setCitySlot(0); city.setName("并发拦截目标"); city.setX(10); city.setY(10);
            city = playerCityRepository.saveAndFlush(city);
            long now = GameClock.now();
            March enemy = createMarch(attacker, "player", city.getId().toString(), city.getName(), 0, 0, 10, 10,
                    Map.of("scout", 10_000), "scout", now - 30_000L, now + 60_000L, false, false);
            March interceptor = createMarch(defender, "incoming_march", enemy.getId().toString(), "设伏部队", 10, 10, 5, 5,
                    Map.of("fighter", 100), "intercept_scout", now - 20_000L, now - 1_000L, false, false);
            interceptor.setInterceptDeployAt(now - 6_000L); interceptor.setInterceptReadyAt(now - 1_000L);
            interceptor.setInterceptEnemyProgress(29_000.0 / 90_000); interceptor.setInterceptEnemyStartAt(enemy.getStartAt());
            interceptor.setInterceptRouteSnapshot(InterceptionPlanner.signature(enemy)); interceptor.setBaseDurationMs(14_000L);
            marchRepository.saveAndFlush(interceptor);
            return new Fixture(attacker, defender, enemy.getId(), now);
        });
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = workers.submit(() -> { await(barrier); marchService.processMarches(f.attacker, f.now); });
            Future<?> d = workers.submit(() -> { await(barrier); marchService.processMarches(f.defender, f.now); });
            a.get(20, TimeUnit.SECONDS); d.get(20, TimeUnit.SECONDS);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertEquals(1, scoutReportRepository.findByPlayerId(f.attacker).size());
                assertEquals(1, scoutReportRepository.findByPlayerId(f.defender).size());
                March survivor = marchRepository.findById(f.enemy).orElseThrow();
                assertTrue(survivor.getAmbushUsed()); assertFalse(survivor.getReturning());
                assertTrue(JsonUtil.parseIntMap(survivor.getArmy()).get("scout") < 10_000);
            });
        } finally { workers.shutdownNow(); }
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
