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
    @Autowired com.wargame.repository.PlayerGuideRepository guides;
    @Autowired com.wargame.repository.MailRepository mails;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCompletionIssuesOneGiftMail() throws Exception {
        var tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(s -> {
            Long id = createTestPlayer("completion-mail-race", 30).getId();
            onboarding.start(id);
            for (var objective : OnboardingService.OBJECTIVES) {
                guides.save(new com.wargame.model.entity.PlayerGuide(id, "ob3_" + objective.id(), "done", System.currentTimeMillis()));
            }
            return id;
        });
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Void> complete = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); onboarding.finish(playerId); return null; };
        try {
            var first = pool.submit(complete); var second = pool.submit(complete);
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertEquals(1, mails.findByToPlayerIdOrderByCreatedAtDesc(playerId).stream()
                    .filter(m -> ("reward:" + playerId).equals(m.getAttachmentSourceId())).count());
            assertEquals(100, getResources(playerId).getDiamond(), "邮件附件尚未领取，只有毕业自动奖励到账");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSupplyClaimsCreditTheMainCityOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(s -> {
            createTestWorld();
            Long id = createTestPlayer("supply-race", 30).getId();
            createBuilding(id, "command", 2); createBuilding(id, "farm", 2); createBuilding(id, "factory", 1);
            onboarding.start(id);
            onboarding.onEvent(id, "BUILD_UPGRADE_DONE", "farm", 1);
            return id;
        });
        long initialFood = getResources(playerId).getFood();
        long initialSteel = getResources(playerId).getSteel();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Void> claim = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); onboarding.claim(playerId, "base"); return null; };
        try {
            Future<Void> a = executor.submit(claim), b = executor.submit(claim);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            assertEquals(initialSteel, getResources(playerId).getSteel());
            assertEquals(initialFood, getResources(playerId).getFood());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSkipsAndClaimsCreditEachSupplyOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(s -> {
            createTestWorld();
            Long id = createTestPlayer("skip-race", 30).getId();
            createBuilding(id, "command", 2); createBuilding(id, "farm", 2); createBuilding(id, "factory", 1);
            onboarding.start(id);
            onboarding.onEvent(id, "BUILD_UPGRADE_DONE", "farm", 1);
            return id;
        });
        long initialFood = getResources(playerId).getFood();
        long initialSteel = getResources(playerId).getSteel();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Void> skip = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); onboarding.skip(playerId); return null; };
        Callable<Void> claim = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); onboarding.claim(playerId, "base"); return null; };
        try {
            Future<Void> first = executor.submit(skip), second = executor.submit(claim);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            onboarding.skip(playerId);
            onboarding.claim(playerId, "base");
            assertEquals(initialSteel + 20000, getResources(playerId).getSteel());
            assertEquals(initialFood + 20000, getResources(playerId).getFood());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
