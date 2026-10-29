package com.wargame;

import com.wargame.model.constants.RankExamDef;
import com.wargame.model.constants.RankGiftDef;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.RankGiftReward;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.RankGiftRewardRepository;
import com.wargame.service.CityScope;
import com.wargame.service.DepotService;
import com.wargame.service.RankGiftService;
import com.wargame.service.RankService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 独立提交事务验证账号锁与失败回滚，避免外层测试事务掩盖重复发钻。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RankDiamondGiftConcurrencyTest extends BaseServiceTest {
    @Autowired RankGiftService gifts;
    @Autowired RankGiftRewardRepository receipts;
    @Autowired PlayerItemRepository items;
    @Autowired RankService ranks;
    @Autowired DepotService depot;
    @Autowired CityScope cities;
    @Autowired PlatformTransactionManager transactions;

    private Player fixture(String name, int tier, boolean legacy) {
        return new TransactionTemplate(transactions).execute(status -> {
            Player player = createTestPlayer(name, 30);
            player.setMilitaryRank(tier);
            player.setRankGiftBaselineTier(legacy ? tier : 0);
            player.setPrestige(10000000);
            player.setNpcVictories(6100);
            playerRepository.save(player);
            return player;
        });
    }

    private void preparePromotion(Long id, int tier) throws Exception {
        items.saveAndFlush(new PlayerItem(null, id, "rank_certificate_" + tier, 1, 0L));
        ranks.startExam(id);
        var exam = JsonUtil.getMapper().readValue(playerRepository.findById(id).orElseThrow().getRankExam(), RankService.Exam.class);
        assertEquals(true, ranks.submitExam(id, new RankService.ExamSubmission(exam.attemptId(),
                exam.questions().stream().map(RankExamDef.Question::answer).toList())).get("passed"));
    }

    private int diamonds(Long id) {
        return Objects.requireNonNullElse(resourcesRepository.findByPlayerIdAndCitySlot(id, 0).orElseThrow().getDiamond(), 0);
    }

    private int stock(Long id, String key) {
        return items.findByPlayerIdAndItemKey(id, key).map(PlayerItem::getCount).orElse(0);
    }

    private RankGiftReward receipt(Long id, int tier) {
        return receipts.findByPlayerIdOrderByRankTierAsc(id).stream().filter(r -> r.getRankTier() == tier).findFirst().orElseThrow();
    }

    /** 同时放行两个独立请求，使竞争实际发生在服务端账号锁上。 */
    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFirstEntriesIssueOnlyOneStarterPack() throws Exception {
        Player player = fixture("rank-gift-first-race", 1, false);
        Callable<Boolean> enter = () -> { gameStateService.enterGame(player.getId()); return true; };
        assertEquals(List.of(true, true), race(enter, enter));
        assertEquals(1, receipts.findByPlayerIdOrderByRankTierAsc(player.getId()).size());
        assertEquals(1, stock(player.getId(), RankGiftDef.key(1)));
        assertEquals(0, diamonds(player.getId()));
    }

    @Test
    void enrollmentRefreshesAPlayerReadBeforeAnotherRequestCommitted() {
        Long id = fixture("rank-gift-preloaded", 1, false).getId();
        var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // 模拟鉴权预读后，另一个请求先完成发放并递增账号 version。
                Player preloaded = playerRepository.findById(id).orElseThrow();
                assertFalse(preloaded.isRankGiftsEnrolled());
                try { pool.submit(() -> gifts.enroll(id)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                gifts.enroll(id);
                assertTrue(preloaded.isRankGiftsEnrolled());
            });
            assertEquals(1, receipts.findByPlayerIdOrderByRankTierAsc(id).size());
            assertEquals(1, stock(id, RankGiftDef.key(1)));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void simultaneousOpenFromDifferentCitiesPaysOnlyOnce() throws Exception {
        Player player = fixture("rank-gift-open-race", 1, false);
        Long id = player.getId();
        gifts.enroll(id);
        List<Boolean> results = race(() -> {
            try (var ignored = cities.enter(id, 0)) {
                return Boolean.TRUE.equals(depot.useItem(id, RankGiftDef.key(1), null, null).get("success"));
            }
        }, () -> {
            try (var ignored = cities.enter(id, 1)) {
                return Boolean.TRUE.equals(depot.useItem(id, RankGiftDef.key(1), null, null).get("success"));
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(20, diamonds(id));
        assertEquals(0, stock(id, RankGiftDef.key(1)));
        assertNotNull(receipt(id, 1).getOpenedAt());
    }

    @Test
    void simultaneousPromotionsCannotReuseThePassedExamOrDuplicateGifts() throws Exception {
        Player player = fixture("rank-gift-promote-race", 1, false);
        Long id = player.getId();
        preparePromotion(id, 2);
        Callable<Boolean> promote = () -> {
            try { return Boolean.TRUE.equals(ranks.promoteRank(id).get("success")); }
            catch (IllegalArgumentException expected) { return false; }
        };
        assertEquals(1, race(promote, promote).stream().filter(Boolean::booleanValue).count());
        assertEquals(2, playerRepository.findById(id).orElseThrow().getMilitaryRank());
        assertNull(playerRepository.findById(id).orElseThrow().getRankExam());
        assertEquals(List.of(1, 2), receipts.findByPlayerIdOrderByRankTierAsc(id).stream().map(RankGiftReward::getRankTier).toList());
        assertEquals(1, stock(id, RankGiftDef.key(1)));
        assertEquals(1, stock(id, RankGiftDef.key(2)));
        assertEquals(0, stock(id, "rank_certificate_2"));
        assertEquals(0, diamonds(id));
    }

    @Test
    void firstLoginAndPromotionRacePreservesBothFrozenAndNewRankRewards() throws Exception {
        Player player = fixture("rank-gift-legacy-race", 3, true);
        Long id = player.getId();
        preparePromotion(id, 4);
        assertEquals(List.of(true, true), race(() -> { gameStateService.enterGame(id); return true; },
                () -> Boolean.TRUE.equals(ranks.promoteRank(id).get("success"))));
        assertEquals(List.of(3, 4), receipts.findByPlayerIdOrderByRankTierAsc(id).stream().map(RankGiftReward::getRankTier).toList());
        assertEquals("legacy", receipt(id, 3).getSource());
        assertEquals("promotion", receipt(id, 4).getSource());
        assertEquals(60, receipt(id, 3).getDiamonds());
        assertEquals(80, receipt(id, 4).getDiamonds());
        assertEquals(1, stock(id, RankGiftDef.key(3)));
        assertEquals(1, stock(id, RankGiftDef.key(4)));
    }

    @Test
    void openingRollbackRestoresPackWalletAndReceiptAndAllowsRetry() {
        Long id = fixture("rank-gift-open-rollback", 1, false).getId();
        gifts.enroll(id);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(true, depot.useItem(id, RankGiftDef.key(1), null, null).get("success"));
            throw new IllegalStateException("模拟开包持久化失败");
        }));
        assertEquals(0, diamonds(id));
        assertEquals(1, stock(id, RankGiftDef.key(1)));
        assertNull(receipt(id, 1).getOpenedAt());
        assertEquals(true, depot.useItem(id, RankGiftDef.key(1), null, null).get("success"));
        assertEquals(20, diamonds(id));
        assertNotNull(receipt(id, 1).getOpenedAt());
    }

    @Test
    void inventoryFailureRollsBackPromotionCertificateExamAndEnrollment() throws Exception {
        Long id = fixture("rank-gift-promote-rollback", 1, true).getId();
        preparePromotion(id, 2);
        // 模拟异常库存，在已升军衔并登记奖励之后触发入仓失败。
        PlayerItem conflict = items.saveAndFlush(new PlayerItem(null, id, RankGiftDef.key(2), 1, 0L));
        assertThrows(IllegalStateException.class, () -> ranks.promoteRank(id));
        Player unchanged = playerRepository.findById(id).orElseThrow();
        assertEquals(1, unchanged.getMilitaryRank());
        assertNotNull(unchanged.getRankExam());
        assertFalse(unchanged.isRankGiftsEnrolled());
        assertEquals(1, stock(id, "rank_certificate_2"));
        assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(id).isEmpty());
        assertEquals(0, stock(id, RankGiftDef.key(1)));
        assertEquals(0, diamonds(id));
        items.deleteById(conflict.getId());
        assertEquals(true, ranks.promoteRank(id).get("success"));
        assertEquals(List.of(1, 2), receipts.findByPlayerIdOrderByRankTierAsc(id).stream().map(RankGiftReward::getRankTier).toList());
    }
}
