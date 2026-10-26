package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.March;
import com.wargame.repository.MailRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 并发 Tick 不代替玩家操作；并发收获和返城也只能结算一次。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GatherAutoAbandonConcurrencyTest extends BaseServiceTest {
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MailRepository mails;

    @Test
    void concurrentTicksLeaveLegacyAutomaticTaskWaitingWithoutChangingResourcesOrOwnership() throws Exception {
        GatherTask task = prepare();
        runConcurrent(() -> marchService.processMarches(task.playerId(), System.currentTimeMillis() + 86_400_000L));
        transaction().executeWithoutResult(status -> {
            March march = marchRepository.findById(task.marchId()).orElseThrow();
            assertTrue(march.getGathering());
            assertFalse(march.getReturning());
            var tile = wildTileRepository.findById(task.tileId()).orElseThrow();
            assertTrue(tile.getOccupied());
            assertEquals(1000, tile.getTotalRes());
            assertEquals(1000, getResources(task.playerId()).getFood());
            assertTrue(mails.findByToPlayerIdOrderByCreatedAtDesc(task.playerId()).isEmpty());
        });
    }

    @Test
    void concurrentManualHarvestAndReturnCreditOnlyOnceAndKeepLand() throws Exception {
        GatherTask task = prepare();
        runConcurrent(() -> marchService.stopGatherMarch(task.playerId(), task.marchId()));
        long returnedAt = transaction().execute(status -> {
            March march = marchRepository.findById(task.marchId()).orElseThrow();
            assertTrue(march.getGatherStopped());
            assertFalse(march.getReturning());
            assertEquals(500, wildTileRepository.findById(task.tileId()).orElseThrow().getTotalRes());
            marchService.cancelMarch(task.playerId(), task.marchId());
            return march.getArriveAt();
        });
        runConcurrent(() -> marchService.processMarches(task.playerId(), returnedAt));
        transaction().executeWithoutResult(status -> {
            assertFalse(marchRepository.existsById(task.marchId()));
            assertEquals(1500, getResources(task.playerId()).getFood());
            assertEquals(10, armyUnitRepository.findByPlayerIdAndType(task.playerId(), "truck").get(0).getCount());
            assertTrue(wildTileRepository.findById(task.tileId()).orElseThrow().getOccupied());
        });
    }

    @Test
    void rolledBackHarvestLeavesTaskUnchangedAndRetrySettlesOnlyOnce() {
        GatherTask task = prepare();
        transaction().executeWithoutResult(status -> {
            marchService.stopGatherMarch(task.playerId(), task.marchId());
            status.setRollbackOnly();
        });
        transaction().executeWithoutResult(status -> {
            assertTrue(marchRepository.findById(task.marchId()).orElseThrow().getGathering());
            assertEquals(1000, wildTileRepository.findById(task.tileId()).orElseThrow().getTotalRes());
            marchService.stopGatherMarch(task.playerId(), task.marchId());
            marchService.stopGatherMarch(task.playerId(), task.marchId());
            assertEquals(500, wildTileRepository.findById(task.tileId()).orElseThrow().getTotalRes());
            assertEquals(1000, getResources(task.playerId()).getFood());
        });
    }

    private GatherTask prepare() {
        return transaction().execute(status -> {
            Long playerId = createTestPlayer("manual-gather-" + java.util.UUID.randomUUID().toString().substring(0, 8), 30).getId();
            var tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
            tile.setOccupied(true);
            tile.setOccupiedBy(playerId);
            wildTileRepository.saveAndFlush(tile);
            createArmyUnit(playerId, "truck", 10);
            March march = marchService.createDispatch(playerId, new DispatchRequest("wild_gather", tile.getId(), "gather",
                    Map.of("truck", 1), null, null));
            marchService.processMarches(playerId, march.getArriveAt());
            march.setGatherStartAt(System.currentTimeMillis() - 120_000L);
            march.setGatherEndAt(System.currentTimeMillis() - 60_000L);
            march.setGatherMode("auto");
            march.setGatherAfterAction("abandon");
            march.setGatherAfterActionResult("pending");
            marchRepository.save(march);
            return new GatherTask(playerId, tile.getId(), march.getId());
        });
    }

    private void runConcurrent(Runnable action) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); action.run(); return null; });
            var second = executor.submit(() -> { start.await(); action.run(); return null; });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private record GatherTask(Long playerId, Long tileId, Long marchId) {}
}
