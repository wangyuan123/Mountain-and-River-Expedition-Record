package com.wargame;

import com.wargame.service.MapFavoriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MapFavoritePersistenceTest extends BaseServiceTest {
    @Autowired private MapFavoriteService favorites;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDeviceRequestsPreserveBothFavoritesAndSurviveReload() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(status -> createTestPlayer("favorite-concurrency", 30).getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Future<?>[] tasks = new Future<?>[2];
            for (int i = 0; i < tasks.length; i++) {
                int x = 10 + i;
                tasks[i] = executor.submit(() -> tx.execute(status -> {
                    // 两个请求都先经过鉴权加载实体，再进入收藏事务的持锁刷新。
                    playerRepository.findById(playerId).orElseThrow(); ready.countDown();
                    try { assertTrue(start.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    return favorites.add(playerId, new MapFavoriteService.SaveRequest(x, 20, "site", null));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
            var stored = tx.execute(status -> favorites.list(playerId));
            assertEquals(2, stored.size());
            assertTrue(stored.stream().anyMatch(f -> f.x() == 10));
            assertTrue(stored.stream().anyMatch(f -> f.x() == 11));
            tx.execute(status -> favorites.remove(playerId, 10, 20));
            assertEquals(1, tx.execute(status -> favorites.list(playerId)).size());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
