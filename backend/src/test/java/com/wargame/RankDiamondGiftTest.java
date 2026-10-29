package com.wargame;

import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.constants.*;
import com.wargame.model.dto.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RankDiamondGiftTest extends BaseServiceTest {
    @Autowired RankGiftService gifts;
    @Autowired RankGiftRewardRepository receipts;
    @Autowired PlayerItemRepository items;
    @Autowired RankService ranks;
    @Autowired DepotService depot;
    @Autowired ShopController shop;
    @Autowired CityScope cities;

    private int diamonds(Long id) {
        return Objects.requireNonNullElse(resourcesRepository.findByPlayerIdAndCitySlot(id, 0).orElseThrow().getDiamond(), 0);
    }
    private RankGiftReward receipt(Long id, int tier) { return receipts.findForUpdate(id, tier).orElseThrow(); }
    private int stock(Long id, String key) { return items.findByPlayerIdAndItemKey(id, key).map(PlayerItem::getCount).orElse(0); }
    private void pass(Long id) throws Exception {
        ranks.startExam(id);
        var exam = JsonUtil.getMapper().readValue(playerRepository.findById(id).orElseThrow().getRankExam(), RankService.Exam.class);
        assertEquals(true, ranks.submitExam(id, new RankService.ExamSubmission(exam.attemptId(),
                exam.questions().stream().map(RankExamDef.Question::answer).toList())).get("passed"));
    }

    @Test
    void firstEntryRequiresInitializationAndNeverReissuesAnOpenedPack() {
        Player p = createTestPlayer();
        p.setGameInitialized(false); playerRepository.saveAndFlush(p);
        gameStateService.enterGame(p.getId());
        assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).isEmpty());
        p.setGameInitialized(true); playerRepository.saveAndFlush(p);
        gameStateService.enterGame(p.getId());
        assertEquals("initial", receipt(p.getId(), 1).getSource());
        assertEquals(20, receipt(p.getId(), 1).getDiamonds());
        assertEquals(1, stock(p.getId(), RankGiftDef.key(1)));
        assertEquals(0, diamonds(p.getId()));
        assertEquals(true, depot.useItem(p.getId(), RankGiftDef.key(1), null, null).get("success"));
        for (int repeat = 0; repeat < 3; repeat++) gameStateService.enterGame(p.getId());
        assertEquals(1, receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).size());
        assertEquals(0, stock(p.getId(), RankGiftDef.key(1)));
        assertEquals(20, diamonds(p.getId()));
        assertEquals(false, depot.useItem(p.getId(), RankGiftDef.key(1), null, null).get("success"));
        assertEquals(20, diamonds(p.getId()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17})
    void legacyAccountsOnlyGetTheFrozenCurrentRank(int tier) {
        Player p = createTestPlayer();
        p.setMilitaryRank(tier); p.setRankGiftBaselineTier(tier); p.setPrestige(123); p.setNpcVictories(77);
        playerRepository.saveAndFlush(p);
        if (tier < 17) items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_" + (tier + 1), 4, 0L));
        gameStateService.enterGame(p.getId());
        gifts.enroll(p.getId());
        var all = receipts.findByPlayerIdOrderByRankTierAsc(p.getId());
        assertEquals(1, all.size());
        assertEquals(tier, all.get(0).getRankTier());
        assertEquals("legacy", all.get(0).getSource());
        int expected = tier == 17 ? 5000 : MilitaryRankDef.getRank(tier + 1).certificatePrice();
        assertEquals(expected, all.get(0).getDiamonds());
        var result = depot.useItem(p.getId(), RankGiftDef.key(tier), null, null);
        assertEquals(true, result.get("success"));
        assertFalse(result.containsKey("nextCertificateTier"));
        assertEquals(expected, diamonds(p.getId()));
        assertEquals(tier, playerRepository.findById(p.getId()).orElseThrow().getMilitaryRank());
        assertEquals(123, p.getPrestige()); assertEquals(77, p.getNpcVictories());
        if (tier < 17) assertEquals(4, stock(p.getId(), "rank_certificate_" + (tier + 1)));
    }

    @Test
    void oldAccountFirstPromotionReceivesBaselineAndNewRankExactlyOnce() throws Exception {
        Player p = createTestPlayer();
        p.setMilitaryRank(3); p.setRankGiftBaselineTier(3);
        p.setPrestige(300000); p.setNpcVictories(110); playerRepository.saveAndFlush(p);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_4", 1, 0L));
        pass(p.getId());
        assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).isEmpty());
        ranks.promoteRank(p.getId());
        gameStateService.enterGame(p.getId());
        assertEquals(List.of(3, 4), receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).stream().map(RankGiftReward::getRankTier).toList());
        assertEquals("legacy", receipt(p.getId(), 3).getSource());
        assertEquals("promotion", receipt(p.getId(), 4).getSource());
    }

    @Test
    void wholePromotionChainFundsEveryCertificateAndEndsWithFiveThousand() throws Exception {
        createTestWorld();
        Player p = createTestPlayer();
        Long id = p.getId();
        p.setPrestige(10000000); p.setNpcVictories(6100); playerRepository.saveAndFlush(p);
        var principal = UserPrincipal.from(p);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            gameStateService.enterGame(id);
            int spent = 0;
            for (int tier = 1; tier <= 17; tier++) {
                RankGiftReward gift = receipt(id, tier);
                int expected = tier == 17 ? 5000 : MilitaryRankDef.getRank(tier + 1).certificatePrice();
                assertEquals(expected, gift.getDiamonds());
                assertEquals(tier == 1 ? "initial" : "promotion", gift.getSource());
                assertEquals(0, diamonds(id), "开包前钻石不变");
                var opened = depot.useItem(id, gift.getItemKey(), null, null);
                assertEquals(true, opened.get("success"));
                assertEquals(expected, diamonds(id));
                assertNotNull(receipt(id, tier).getOpenedAt());
                if (tier == 17) { assertFalse(opened.containsKey("nextCertificateTier")); break; }
                assertEquals(tier + 1, opened.get("nextCertificateTier"));
                var target = MilitaryRankDef.getRank(tier + 1);
                var bought = shop.buy(new GameDtos.ShopBuyRequest(target.certificateKey())).getBody();
                assertEquals(true, bought.get("success"));
                spent += (int) bought.get("totalCost");
                assertEquals(0, diamonds(id));
                pass(id);
                assertEquals(tier, receipts.findByPlayerIdOrderByRankTierAsc(id).size(), "笔试合格不发晋升礼包");
                var promotion = ranks.promoteRank(id);
                assertEquals(RankGiftDef.key(tier + 1), ((Map<?, ?>) promotion.get("promotionReward")).get("itemKey"));
                assertTrue(promotion.get("message").toString().contains("已入仓"));
                assertEquals(1, stock(id, RankGiftDef.key(tier + 1)));
                assertEquals(0, stock(id, target.certificateKey()));
            }
            assertEquals(10950, spent);
            assertEquals(15950, receipts.findByPlayerIdOrderByRankTierAsc(id).stream().mapToInt(RankGiftReward::getDiamonds).sum());
            assertEquals(5000, diamonds(id));
            assertEquals(0, gifts.state(id).get("unopenedCount"));
            assertThrows(IllegalStateException.class, () -> ranks.promoteRank(id));
            assertEquals(17, receipts.findByPlayerIdOrderByRankTierAsc(id).size());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void openUsesPromisedAmountAndMainCityWalletWhileViewingAnotherCity() {
        WorldMap world = createTestWorld();
        Player p = createTestPlayer(); gifts.enroll(p.getId());
        RankGiftReward gift = receipt(p.getId(), 1);
        gift.setDiamonds(37); receipts.saveAndFlush(gift);
        PlayerCity city = new PlayerCity(); city.setWorldId(world.getId()); city.setOwnerId(p.getId());
        city.setCitySlot(1); city.setName("分城"); city.setX(20); city.setY(20); playerCityRepository.saveAndFlush(city);
        Resources branch = new Resources(); branch.setPlayerId(p.getId()); branch.setCitySlot(1); branch.setDiamond(900);
        resourcesRepository.saveAndFlush(branch);
        assertTrue(((List<?>) gifts.state(p.getId()).get("rewards")).get(0).toString().contains("37"));
        var principal = UserPrincipal.from(p);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try (var ignored = cities.enter(p.getId(), 1)) {
            var result = depot.useItem(p.getId(), RankGiftDef.key(1), null, null);
            assertEquals(37, result.get("diamonds"));
            assertEquals(true, result.get("success"));
            assertEquals(37, diamonds(p.getId()));
            assertEquals(true, shop.buy(new GameDtos.ShopBuyRequest("rank_certificate_2")).getBody().get("success"));
        } finally { SecurityContextHolder.clearContext(); }
        assertEquals(17, diamonds(p.getId()));
        assertEquals(1, stock(p.getId(), "rank_certificate_2"));
        assertEquals(900, resourcesRepository.findByPlayerIdAndCitySlot(p.getId(), 1).orElseThrow().getDiamond());
    }

    @Test
    void overflowMissingInventoryAndForgedTierCannotConsumeOrPay() {
        Player p = createTestPlayer(); gifts.enroll(p.getId());
        Resources wallet = resourcesRepository.findByPlayerIdAndCitySlot(p.getId(), 0).orElseThrow();
        wallet.setDiamond(Integer.MAX_VALUE - 19); resourcesRepository.saveAndFlush(wallet);
        assertEquals(false, depot.useItem(p.getId(), RankGiftDef.key(1), null, null).get("success"));
        assertEquals(1, stock(p.getId(), RankGiftDef.key(1))); assertNull(receipt(p.getId(), 1).getOpenedAt());
        wallet.setDiamond(Integer.MAX_VALUE - 20); resourcesRepository.saveAndFlush(wallet);
        assertEquals(true, depot.useItem(p.getId(), RankGiftDef.key(1), null, null).get("success"));
        assertEquals(Integer.MAX_VALUE, diamonds(p.getId()));
        items.saveAndFlush(new PlayerItem(null, p.getId(), RankGiftDef.key(17), 1, 0L));
        for (String key : List.of(RankGiftDef.key(17), "rank_diamond_pack_01", "rank_diamond_pack_0", "rank_diamond_pack_18", "rank_diamond_pack_1junk")) {
            assertEquals(false, depot.useItem(p.getId(), key, null, null).get("success"));
        }
        assertEquals(Integer.MAX_VALUE, diamonds(p.getId()));
        assertEquals(1, stock(p.getId(), RankGiftDef.key(17)));
    }

    @Test
    void missingPackAndBulkUseDoNotOpenTheReceipt() {
        Player p = createTestPlayer(); gifts.enroll(p.getId());
        assertEquals(false, depot.useItem(p.getId(), RankGiftDef.key(1), null, null, null, 2, null).get("success"));
        items.delete(items.findByPlayerIdAndItemKey(p.getId(), RankGiftDef.key(1)).orElseThrow()); items.flush();
        assertEquals(false, depot.useItem(p.getId(), RankGiftDef.key(1), null, null).get("success"));
        assertNull(receipt(p.getId(), 1).getOpenedAt()); assertEquals(0, diamonds(p.getId()));
    }

    @Test
    void giftKeysCannotBePurchasedFromTheShop() {
        Player p = createTestPlayer();
        var principal = UserPrincipal.from(p);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            for (int tier = 1; tier <= 17; tier++) {
                assertEquals(false, shop.buy(new GameDtos.ShopBuyRequest(RankGiftDef.key(tier))).getBody().get("success"));
                assertEquals(0, stock(p.getId(), RankGiftDef.key(tier)));
            }
            assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).isEmpty());
            assertEquals(0, diamonds(p.getId()));
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void failureNpcBattleAndEarlyExamDoNotGrantPromotionPacks() throws Exception {
        Player p = createTestPlayer();
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(p.getId()));
        BattleResult win = new BattleResult(); win.setWin(true); win.setInitialDefender(Map.of("infantry", 1));
        Bandit target = new Bandit(); target.setLevel(1); ranks.awardNpcVictory(p.getId(), target, win);
        assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).isEmpty());
        p.setPrestige(10000); p.setNpcVictories(10); playerRepository.saveAndFlush(p);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_2", 1, 0L));
        pass(p.getId());
        assertTrue(receipts.findByPlayerIdOrderByRankTierAsc(p.getId()).isEmpty());
        assertEquals(0, diamonds(p.getId()));
    }
}
