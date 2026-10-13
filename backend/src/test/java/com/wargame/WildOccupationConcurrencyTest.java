package com.wargame;

import com.wargame.model.entity.WildTile;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.WildOccupationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证名额争夺和等待账号锁后读取的回合状态，避免超额占领或重复结算。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WildOccupationConcurrencyTest extends BaseServiceTest {
    @Autowired WildOccupationService occupation;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BattleSessionRepository sessions;

    @Test void simultaneousWinningBattlesCannotExceedCityLimit() throws Exception {
        Long playerId = createTestPlayer("wild-race-" + UUID.randomUUID(), 30).getId();
        Long worldId = createTestWorld().getId();
        createBuilding(playerId, "command", 1);
        for (int i = 0; i < 5; i++) {
            WildTile tile = createWildTile(worldId, "forest", 30 + i, 20, 1, Map.of(), 0);
            tile.setOccupied(true); tile.setOccupiedBy(playerId); wildTileRepository.save(tile);
        }
        Long firstTarget = createWildTile(worldId, "forest", 40, 20, 1, Map.of(), 0).getId();
        Long secondTarget = createWildTile(worldId, "forest", 41, 20, 1, Map.of(), 0).getId();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<Long, Callable<Boolean>> claim = id -> () -> new TransactionTemplate(transactions).execute(status -> {
                WildTile target = wildTileRepository.findById(id).orElseThrow();
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
                return occupation.claim(playerId, 0, target).claimed();
            });
            Future<Boolean> first = workers.submit(claim.apply(firstTarget));
            Future<Boolean> second = workers.submit(claim.apply(secondTarget));
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(6L, occupation.limit(playerId, 0).ownedCount());
        } finally { workers.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "manual"})
    void cachedRoundCannotBeResolvedAgainAfterAnotherTransactionAdvances(String trigger) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = createTestPlayer("wild-round-" + UUID.randomUUID(), 30).getId();
        Long worldId = createTestWorld().getId();
        long[] battle = tx.execute(status -> {
            giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
            createArmyUnit(playerId, "infantry", 100);
            WildTile target = createWildTile(worldId, "forest", 15, 15, 1, Map.of("truck", 100000), 0);
            March march = marchService.createDispatch(playerId, new DispatchRequest("wild", target.getId(), "conquer",
                    Map.of("infantry", 100), null, null, null, "manual"));
            marchService.processMarches(playerId, march.getArriveAt());
            BattleSession session = sessions.findByMarchId(march.getId()).orElseThrow();
            session.setRoundDeadlineAt(System.currentTimeMillis() - 1);
            return new long[] {march.getId(), session.getId(), session.getRoundDeadlineAt()};
        });
        CountDownLatch readOldRound = new CountDownLatch(1);
        CountDownLatch advanced = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> staleRequest = worker.submit(() -> tx.executeWithoutResult(status -> {
                // 模拟请求在等待账号锁前已把行军和回合放进一级缓存。
                marchRepository.findById(battle[0]).orElseThrow();
                assertEquals(0, sessions.findById(battle[1]).orElseThrow().getRoundNo());
                readOldRound.countDown();
                try { assertTrue(advanced.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                if ("timeout".equals(trigger)) marchService.processTimedOutTacticalBattle(battle[1], battle[2]);
                else assertEquals(1, marchService.executeTacticalRound(playerId, battle[0],
                        new GameDtos.BattleCommandRequest(Map.of(), 0)).get("round"));
            }));
            assertTrue(readOldRound.await(10, TimeUnit.SECONDS));
            tx.executeWithoutResult(status -> assertEquals(1, marchService.executeTacticalRound(playerId, battle[0],
                    new GameDtos.BattleCommandRequest(Map.of(), 0)).get("round")));
            advanced.countDown();
            staleRequest.get(20, TimeUnit.SECONDS);
            assertEquals(1, sessions.findById(battle[1]).orElseThrow().getRoundNo());
            assertTrue(scoutReportRepository.findByPlayerId(playerId).isEmpty());
        } finally { advanced.countDown(); worker.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"target", "self-target", "hall"})
    void claimReadsCurrentOwnershipAndCityHallAfterAnEarlierSnapshot(String changed) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = createTestPlayer("wild-snapshot-" + UUID.randomUUID(), 30).getId();
        Long otherPlayerId = createTestPlayer("wild-owner-" + UUID.randomUUID(), 30).getId();
        Long newOwnerId = "self-target".equals(changed) ? playerId : otherPlayerId;
        Long worldId = createTestWorld().getId();
        Long hallId = createBuilding(playerId, "command", 1).getId();
        Long targetId = createWildTile(worldId, "forest", 45, 20, 1, Map.of(), 0).getId();
        if ("hall".equals(changed)) {
            for (int i = 0; i < 6; i++) {
                WildTile owned = createWildTile(worldId, "forest", 50 + i, 20, 1, Map.of(), 0);
                owned.setOccupied(true); owned.setOccupiedBy(playerId); wildTileRepository.save(owned);
            }
        }
        CountDownLatch readSnapshot = new CountDownLatch(1);
        CountDownLatch changedState = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<WildOccupationService.Claim> staleClaim = worker.submit(() -> tx.execute(status -> {
                WildTile target = wildTileRepository.findById(targetId).orElseThrow();
                buildingRepository.findById(hallId).orElseThrow();
                readSnapshot.countDown();
                try { assertTrue(changedState.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return occupation.claim(playerId, 0, target);
            }));
            assertTrue(readSnapshot.await(10, TimeUnit.SECONDS));
            tx.executeWithoutResult(status -> {
                if (!"hall".equals(changed)) assertTrue(occupation.claim(newOwnerId, 0,
                        wildTileRepository.findById(targetId).orElseThrow()).claimed());
                else buildingRepository.findById(hallId).orElseThrow().setLevel(10);
            });
            changedState.countDown();
            WildOccupationService.Claim result = staleClaim.get(20, TimeUnit.SECONDS);
            assertEquals("hall".equals(changed), result.claimed());
            if (!"hall".equals(changed)) {
                assertTrue(result.reason().contains("已被占领"));
                assertEquals(newOwnerId, wildTileRepository.findById(targetId).orElseThrow().getOccupiedBy());
            } else assertEquals(15, occupation.limit(playerId, 0).cap());
        } finally { changedState.countDown(); worker.shutdownNow(); }
    }
}
