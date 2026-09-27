package com.wargame.service;

import com.wargame.BaseServiceTest;
import com.wargame.model.entity.Player;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AcademyRefreshConcurrencyTest extends BaseServiceTest {
    @Autowired private OfficerService officers;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRequestsCannotExceedThirtyRefreshesPerRound() throws Exception {
        assertOneRemainingRefresh(29, 29);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRequestsCannotExceedOneHundredRefreshesPerDay() throws Exception {
        assertOneRemainingRefresh(9, 99);
    }

    private void assertOneRemainingRefresh(int roundCount, int dailyCount) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(status -> {
            Player player = createTestPlayer("academy-race-" + dailyCount, 30);
            createBuilding(player.getId(), "academy", 1);
            player.setAcademyRefreshDay(LocalDate.now(ZoneId.of("Asia/Shanghai")));
            player.setAcademyRefreshRoundCount(roundCount);
            player.setAcademyRefreshDailyCount(dailyCount);
            playerRepository.save(player);
            return player.getId();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Map<String, Object>> refresh = () -> tx.execute(status -> {
            // 模拟鉴权已将旧玩家对象载入请求持久化上下文。
            playerRepository.findById(playerId).orElseThrow();
            ready.countDown();
            try {
                assertTrue(start.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return officers.refreshAcademy(playerId);
        });
        try {
            Future<Map<String, Object>> first = executor.submit(refresh), second = executor.submit(refresh);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            boolean firstSuccess = (Boolean) first.get(20, TimeUnit.SECONDS).get("success");
            boolean secondSuccess = (Boolean) second.get(20, TimeUnit.SECONDS).get("success");
            assertNotEquals(firstSuccess, secondSuccess, "只剩一次额度时只能成功一次");
            Player player = playerRepository.findById(playerId).orElseThrow();
            assertEquals(roundCount + 1, player.getAcademyRefreshRoundCount());
            assertEquals(dailyCount + 1, player.getAcademyRefreshDailyCount());
            assertEquals(300, getResources(playerId).getGold());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
