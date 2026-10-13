package com.wargame;

import com.wargame.model.entity.March;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
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
class MarchAccelerationConcurrencyTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @Autowired PlatformTransactionManager transactions;

    private March prepare() {
        Long playerId = createTestPlayer("accel-" + UUID.randomUUID(), 30).getId();
        items.save(new PlayerItem(null, playerId, "marchOrd", 3, System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        return createMarch(playerId, "wild", "0", "并发行军", 10, 10, 20, 20,
                Map.of("infantry", 10), "scout", now - 120_000, now + 180_000, false, false);
    }

    @Test void simultaneousRequestsConsumeOnlyOneOrder() throws Exception {
        March march = prepare();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> speedUp = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                marchService.speedUpMarch(march.getPlayerId(), march.getId(), false, march.getVersion());
                return true;
            } catch (IllegalArgumentException stale) { return false; }
        };
        try {
            Future<Boolean> first = workers.submit(speedUp), second = workers.submit(speedUp);
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(2, items.findByPlayerIdAndItemKey(march.getPlayerId(), "marchOrd").orElseThrow().getCount());
            assertTrue(marchRepository.findById(march.getId()).orElseThrow().getOutboundAccelerated());
        } finally { workers.shutdownNow(); }
    }

    @Test void rollbackRestoresConsumedOrderAndMarchClocks() {
        March march = prepare();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            marchService.speedUpMarch(march.getPlayerId(), march.getId(), false, march.getVersion());
            assertEquals(2, items.findByPlayerIdAndItemKey(march.getPlayerId(), "marchOrd").orElseThrow().getCount());
            status.setRollbackOnly();
        });
        March restored = marchRepository.findById(march.getId()).orElseThrow();
        assertEquals(march.getArriveAt(), restored.getArriveAt());
        assertFalse(restored.getOutboundAccelerated());
        assertNull(restored.getBaseDurationMs());
        assertEquals(3, items.findByPlayerIdAndItemKey(march.getPlayerId(), "marchOrd").orElseThrow().getCount());
    }
}
