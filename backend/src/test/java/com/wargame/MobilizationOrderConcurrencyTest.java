package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ArmyService;
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

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MobilizationOrderConcurrencyTest extends BaseServiceTest {
    @Autowired private PlayerItemRepository items;
    @Autowired private ArmyService armyService;
    @Autowired private PlatformTransactionManager transactions;

    private record Fixture(Long playerId, DispatchRequest request) {}

    private Fixture fixture(String name) {
        return new TransactionTemplate(transactions).execute(status -> {
            Long player = createTestPlayer(name, 30).getId();
            var tile = createWildTile(createTestWorld().getId(), "forest", 15, 15, 1, Map.of(), 1000);
            createArmyUnit(player, "infantry", 100_000);
            giveResources(player, 100_000, 100_000, 1_000_000, 100_000, 100_000);
            items.saveAndFlush(new PlayerItem(null, player, "mobilizationOrder", 1, 0L));
            int troops = armyService.armyCap(player) + 1;
            var request = new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", troops),
                    null, null, null, null, null, null, null, null, null, null, "mobilizationOrder");
            return new Fixture(player, request);
        });
    }

    @Test
    void concurrentDispatchesCannotSpendLastOrderTwice() throws Exception {
        Fixture f = fixture("mobilization-race");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> dispatch = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                marchService.createDispatch(f.playerId(), f.request());
                return true;
            } catch (IllegalArgumentException error) {
                assertEquals("战争动员令数量不足", error.getMessage());
                return false;
            }
        };
        try {
            var first = pool.submit(dispatch);
            var second = pool.submit(dispatch);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(0, items.findByPlayerIdAndItemKey(f.playerId(), "mobilizationOrder").orElseThrow().getCount());
            assertEquals(1, marchRepository.findByPlayerId(f.playerId()).size());
            assertEquals(100_000 - f.request().army().get("infantry"),
                    armyUnitRepository.findByPlayerIdAndType(f.playerId(), "infantry").get(0).getCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failureAfterCreatingMarchRollsBackOrderTroopsAndFuel() {
        Fixture f = fixture("mobilization-rollback");
        long oil = getResources(f.playerId()).getOil();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            marchService.createDispatch(f.playerId(), f.request());
            marchRepository.flush();
            throw new IllegalStateException("模拟出征创建后事务失败");
        }));
        assertEquals(1, items.findByPlayerIdAndItemKey(f.playerId(), "mobilizationOrder").orElseThrow().getCount());
        assertEquals(100_000, armyUnitRepository.findByPlayerIdAndType(f.playerId(), "infantry").get(0).getCount());
        assertEquals(oil, getResources(f.playerId()).getOil());
        assertTrue(marchRepository.findByPlayerId(f.playerId()).isEmpty());
    }
}
