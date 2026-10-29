package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.entity.*;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerQuestRepository;
import com.wargame.service.RankService;
import com.wargame.service.quest.QuestCatalog;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RankPromotionTest extends BaseServiceTest {
    @Autowired RankService ranks;
    @Autowired PlayerItemRepository items;
    @Autowired PlayerQuestRepository playerQuests;

    private Player eligible(int currentTier) {
        Player player = createTestPlayer();
        var next = MilitaryRankDef.getRank(currentTier + 1);
        player.setMilitaryRank(currentTier);
        player.setPrestige(next.prestige());
        player.setNpcVictories(next.npcVictories());
        playerRepository.saveAndFlush(player);
        items.saveAndFlush(new PlayerItem(null, player.getId(), next.certificateKey(), 1, 0L));
        return player;
    }

    private RankService.Exam stored(Long id) throws Exception {
        return JsonUtil.getMapper().readValue(playerRepository.findById(id).orElseThrow().getRankExam(), RankService.Exam.class);
    }

    private Map<String, Object> pass(Long id, int mistakes) throws Exception {
        ranks.startExam(id);
        var exam = stored(id);
        List<Integer> answers = new ArrayList<>(exam.questions().stream().map(RankExamDef.Question::answer).toList());
        for (int i = 0; i < mistakes; i++) answers.set(i, (answers.get(i) + 1) % 4);
        return ranks.submitExam(id, new RankService.ExamSubmission(exam.attemptId(), answers));
    }

    @ParameterizedTest
    @CsvSource({"1,10000", "2,100000", "3,300000", "4,500000", "5,1000000", "6,1500000",
            "7,2000000", "8,2600000", "9,3300000", "10,4100000", "11,5000000", "12,6000000",
            "13,7000000", "14,8000000", "15,9000000", "16,10000000"})
    void newPrestigeThresholdControlsExamAndPromotion(int currentTier, int threshold) throws Exception {
        Player player = eligible(currentTier);
        Long id = player.getId();
        player.setPrestige(threshold - 1); playerRepository.saveAndFlush(player);
        var info = ranks.getRankInfo(id);
        assertEquals(threshold, info.get("reqPrestige"));
        assertEquals(false, info.get("prestigeEnough"));
        assertEquals(false, info.get("canTakeExam"));
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(id));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals(currentTier, playerRepository.findById(id).orElseThrow().getMilitaryRank());
        assertEquals(1, items.findByPlayerIdAndItemKey(id, "rank_certificate_" + (currentTier + 1)).orElseThrow().getCount());

        player.setPrestige(threshold); playerRepository.saveAndFlush(player);
        assertEquals(true, ranks.getRankInfo(id).get("canTakeExam"));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals(true, pass(id, 0).get("passed"));
        ranks.promoteRank(id);
        Player promoted = playerRepository.findById(id).orElseThrow();
        assertEquals(currentTier + 1, promoted.getMilitaryRank());
        assertEquals(threshold, promoted.getPrestige());
        assertEquals(MilitaryRankDef.getRank(currentTier + 1).npcVictories(), promoted.getNpcVictories());
        assertEquals(0, items.findByPlayerIdAndItemKey(id, "rank_certificate_" + (currentTier + 1)).orElseThrow().getCount());
    }

    @ParameterizedTest
    @CsvSource({"1,10", "2,50", "3,110", "4,200", "5,300", "6,450", "7,650", "8,900",
            "9,1200", "10,1600", "11,2100", "12,2700", "13,3400", "14,4200", "15,5100", "16,6100"})
    void cumulativeVictoryThresholdControlsExamAndRechecksPromotion(int currentTier, int threshold) throws Exception {
        Player player = eligible(currentTier);
        Long id = player.getId();
        var next = MilitaryRankDef.getRank(currentTier + 1);
        player.setNpcVictories(threshold - 1); playerRepository.saveAndFlush(player);
        var info = ranks.getRankInfo(id);
        assertEquals(threshold, info.get("reqNpcVictories"));
        assertEquals((long) threshold - 1, info.get("npcVictories"));
        assertEquals(false, info.get("victoriesEnough"));
        assertEquals(false, info.get("canTakeExam"));
        assertEquals(false, info.get("canPromote"));
        assertEquals("累计剿寇胜场不足，需要 " + threshold + " 场",
                assertThrows(IllegalArgumentException.class, () -> ranks.startExam(id)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals(currentTier, playerRepository.findById(id).orElseThrow().getMilitaryRank());
        assertEquals(1, items.findByPlayerIdAndItemKey(id, next.certificateKey()).orElseThrow().getCount());

        player.setNpcVictories(threshold); playerRepository.saveAndFlush(player);
        assertEquals(true, ranks.getRankInfo(id).get("victoriesEnough"));
        assertEquals(true, ranks.getRankInfo(id).get("canTakeExam"));
        assertEquals(true, pass(id, 0).get("passed"));
        assertEquals(true, ranks.getRankInfo(id).get("canPromote"));

        // 已合格笔试不豁免新胜场门槛；不足时保留成绩、军衔和凭证，补足后可继续授衔。
        player = playerRepository.findById(id).orElseThrow();
        player.setNpcVictories(threshold - 1); playerRepository.saveAndFlush(player);
        info = ranks.getRankInfo(id);
        assertEquals(true, info.get("examPassed"));
        assertEquals(false, info.get("canPromote"));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals(currentTier, playerRepository.findById(id).orElseThrow().getMilitaryRank());
        assertEquals(1, items.findByPlayerIdAndItemKey(id, next.certificateKey()).orElseThrow().getCount());

        player.setNpcVictories(threshold + 1); playerRepository.saveAndFlush(player);
        assertEquals(true, ranks.getRankInfo(id).get("canPromote"));
        ranks.promoteRank(id);
        Player promoted = playerRepository.findById(id).orElseThrow();
        assertEquals(currentTier + 1, promoted.getMilitaryRank());
        assertEquals(threshold + 1, promoted.getNpcVictories());
        assertEquals(next.prestige(), promoted.getPrestige());
        assertEquals(0, items.findByPlayerIdAndItemKey(id, next.certificateKey()).orElseThrow().getCount());
    }

    @Test
    void historicalRankAndPassedExamRemainUntilNewThresholdsAllowPromotion() throws Exception {
        Player player = eligible(15);
        Long id = player.getId();
        pass(id, 0);
        player = playerRepository.findById(id).orElseThrow();
        // 模拟旧门槛下已授衔并通过下一阶笔试的账号，读资格不能降级或清除成绩。
        player.setPrestige(1_200_000); player.setNpcVictories(1100); playerRepository.saveAndFlush(player);
        var info = ranks.getRankInfo(id);
        assertEquals(15, info.get("tier"));
        assertEquals("少将", info.get("name"));
        assertEquals(8, info.get("cityCap"));
        assertEquals(8, info.get("nextCityCap"));
        assertNull(info.get("nextCityRankName"));
        assertEquals(500000, info.get("baseCap"));
        assertEquals(true, info.get("examPassed"));
        assertEquals(1100L, info.get("npcVictories"));
        assertEquals(5100, info.get("reqNpcVictories"));
        assertEquals(false, info.get("victoriesEnough"));
        assertEquals(false, info.get("canPromote"));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));

        player.setPrestige(9_000_000); playerRepository.saveAndFlush(player);
        assertEquals(false, ranks.getRankInfo(id).get("canPromote"));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        player.setNpcVictories(5100); playerRepository.saveAndFlush(player);
        assertEquals(true, ranks.getRankInfo(id).get("canPromote"));
        assertEquals(15, playerRepository.findById(id).orElseThrow().getMilitaryRank());
        ranks.promoteRank(id);
        assertEquals(16, playerRepository.findById(id).orElseThrow().getMilitaryRank());
    }

    @Test
    void missingPrerequisitesAndExamPreventPromotionWithoutConsumingItems() {
        Player p = createTestPlayer();
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(p.getId()));
        p.setPrestige(10000); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(p.getId()));
        p.setNpcVictories(10); playerRepository.saveAndFlush(p);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_3", 10, 0L));
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(p.getId()));
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_2", 2, 0L));
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(p.getId()));
        assertEquals(2, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow().getCount());
        assertEquals(1, playerRepository.findById(p.getId()).orElseThrow().getMilitaryRank());
    }

    @Test
    void resumeUsesSamePaperAndNeverReturnsAnswers() throws Exception {
        Player p = eligible(1);
        var paper = ranks.startExam(p.getId());
        var again = ranks.startExam(p.getId());
        // 恢复试卷时服务器时间会变化，但试卷身份、题目和截止时间必须保持一致。
        assertEquals(paper.get("attemptId"), again.get("attemptId"));
        assertEquals(paper.get("tier"), again.get("tier"));
        assertEquals(paper.get("rankName"), again.get("rankName"));
        assertEquals(paper.get("expiresAt"), again.get("expiresAt"));
        assertEquals(paper.get("requiredCorrect"), again.get("requiredCorrect"));
        assertEquals(paper.get("questions"), again.get("questions"));
        assertFalse(JsonUtil.toJson(paper).contains("\"answer\""));
        assertFalse(JsonUtil.toJson(ranks.getRankInfo(p.getId())).contains("\"questions\""));
        assertFalse(JsonUtil.toJson(playerRepository.findById(p.getId()).orElseThrow()).contains("rankExam"));
        assertEquals(5, stored(p.getId()).questions().size());
        assertEquals(1, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow().getCount());
    }

    @Test
    void eightyPercentPassesConsumesOneExactCertificateAndUpdatesMainQuest() throws Exception {
        Player p = eligible(1);
        PlayerItem certificate = items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow();
        certificate.setCount(2); items.saveAndFlush(certificate);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_3", 9, 0L));
        items.saveAndFlush(new PlayerItem(null, p.getId(), "expBook", 50, 0L));
        var grade = pass(p.getId(), 1);
        assertEquals(true, grade.get("passed"));
        assertEquals(4, grade.get("correct"));
        assertTrue(playerQuests.findByPlayerIdAndQuestId(p.getId(), "q1_9").isEmpty(), "仅通过笔试不应完成授衔任务");
        String attempt = stored(p.getId()).attemptId();
        assertThrows(IllegalStateException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission(attempt, List.of(0, 0, 0, 0, 0))));
        ranks.promoteRank(p.getId());
        Player promoted = playerRepository.findById(p.getId()).orElseThrow();
        assertEquals(2, promoted.getMilitaryRank());
        assertEquals(10000, promoted.getPrestige());
        assertEquals(10, promoted.getNpcVictories());
        assertNull(promoted.getRankExam());
        assertEquals(1, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow().getCount());
        assertEquals(9, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_3").orElseThrow().getCount());
        assertEquals(50, items.findByPlayerIdAndItemKey(p.getId(), "expBook").orElseThrow().getCount());
        assertEquals(2, playerQuests.findByPlayerIdAndQuestId(p.getId(), "q1_9").orElseThrow().getProgress());
        assertThrows(IllegalArgumentException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission(attempt, List.of())));
    }

    @Test
    void promotionRechecksPrestigeVictoriesAndInventoryAfterPassingExam() throws Exception {
        Player p = eligible(1);
        pass(p.getId(), 0);
        p = playerRepository.findById(p.getId()).orElseThrow();
        Long id = p.getId();
        p.setPrestige(9999); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        p.setPrestige(10000); p.setNpcVictories(9); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        p.setNpcVictories(10); playerRepository.saveAndFlush(p);
        var item = items.findByPlayerIdAndItemKey(id, "rank_certificate_2").orElseThrow();
        item.setCount(0); items.saveAndFlush(item);
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals(1, playerRepository.findById(id).orElseThrow().getMilitaryRank());
    }

    @Test
    void failedExamPersistsCooldownAndDoesNotConsumeCertificate() throws Exception {
        Player p = eligible(1);
        var result = pass(p.getId(), 2);
        assertEquals(false, result.get("passed"));
        assertTrue(stored(p.getId()).retryAt() > System.currentTimeMillis());
        assertThrows(IllegalStateException.class, () -> ranks.startExam(p.getId()));
        assertEquals(1, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow().getCount());
        var exam = stored(p.getId());
        p.setRankExam(JsonUtil.toJson(new RankService.Exam(exam.attemptId(), 2, exam.expiresAt(), "failed", 0, 3, exam.questions())));
        playerRepository.saveAndFlush(p);
        assertNotEquals(exam.attemptId(), ranks.startExam(p.getId()).get("attemptId"));
    }

    @Test
    void expiredExamCannotPassOrBeReplacedDuringCooldown() throws Exception {
        Player p = eligible(1);
        ranks.startExam(p.getId());
        var exam = stored(p.getId());
        long expiredAt = System.currentTimeMillis() - 1000;
        p.setRankExam(JsonUtil.toJson(new RankService.Exam(exam.attemptId(), 2, expiredAt, "active", 0, 0, exam.questions())));
        playerRepository.saveAndFlush(p);
        assertThrows(IllegalStateException.class, () -> ranks.startExam(p.getId()));
        var result = ranks.submitExam(p.getId(), new RankService.ExamSubmission(exam.attemptId(), exam.questions().stream().map(RankExamDef.Question::answer).toList()));
        assertEquals(false, result.get("passed"));
        assertEquals(0, result.get("correct"));
        assertEquals(expiredAt + RankExamDef.COOLDOWN_MS, result.get("retryAt"));
    }

    @Test
    void invalidAnswersAndAttemptCannotChangeExam() throws Exception {
        Player p = eligible(1);
        ranks.startExam(p.getId());
        var exam = stored(p.getId());
        for (var answers : List.of(List.of(0), List.of(4, 0, 0, 0, 0), List.of(-1, 0, 0, 0, 0))) {
            assertThrows(IllegalArgumentException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission(exam.attemptId(), answers)));
        }
        assertThrows(IllegalArgumentException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission("wrong", List.of())));
        assertEquals("active", stored(p.getId()).status());
    }

    @Test
    void eightQuestionsRequireSevenCorrectAndNextRankRequiresNewExam() throws Exception {
        Player p = eligible(7);
        assertEquals(false, pass(p.getId(), 2).get("passed"));
        var exam = stored(p.getId());
        p.setRankExam(JsonUtil.toJson(new RankService.Exam(exam.attemptId(), 8, exam.expiresAt(), "failed", 0, 6, exam.questions())));
        playerRepository.saveAndFlush(p);
        assertEquals(true, pass(p.getId(), 1).get("passed"));
        ranks.promoteRank(p.getId());
        p = playerRepository.findById(p.getId()).orElseThrow();
        p.setPrestige(2600000); p.setNpcVictories(900); playerRepository.saveAndFlush(p);
        items.saveAndFlush(new PlayerItem(null, p.getId(), "rank_certificate_9", 1, 0L));
        Long id = p.getId();
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        assertEquals("not_started", ((Map<?, ?>) ranks.getRankInfo(id).get("exam")).get("status"));
    }

    @Test
    void onlyNonemptyNpcVictoriesCount() {
        Player p = createTestPlayer();
        Bandit bandit = new Bandit(); bandit.setLevel(1);
        NpcCity city = new NpcCity(); city.setLevel(30);
        BattleResult win = new BattleResult(); win.setWin(true); win.setInitialDefender(Map.of("infantry", 10));
        ranks.awardNpcVictory(p.getId(), bandit, win);
        ranks.awardNpcVictory(p.getId(), city, win);
        for (Object target : List.of(new PlayerCity(), new WildTile())) ranks.awardNpcVictory(p.getId(), target, win);
        win.setWin(false); ranks.awardNpcVictory(p.getId(), bandit, win);
        win.setWin(true); win.setInitialDefender(Map.of()); ranks.awardNpcVictory(p.getId(), city, win);
        assertEquals(2, playerRepository.findById(p.getId()).orElseThrow().getNpcVictories());
    }

    @Test
    void npcVictoriesAtEveryLevelNeverAwardCertificatesOrChangeLegacyInventory() {
        Player player = createTestPlayer();
        items.saveAndFlush(new PlayerItem(null, player.getId(), "rank_certificate_2", 4, 0L));
        for (int level = 1; level <= 30; level++) {
            Bandit bandit = new Bandit(); bandit.setLevel(level);
            NpcCity city = new NpcCity(); city.setLevel(level);
            for (Object target : List.of(bandit, city)) {
                BattleResult win = new BattleResult();
                win.setWin(true); win.setInitialDefender(Map.of("infantry", 10));
                ranks.awardNpcVictory(player.getId(), target, win);
                assertTrue(win.getReport().contains("剿寇胜场 +1"));
                assertFalse(win.getReport().contains("晋升凭证"));
            }
        }
        assertEquals(60, playerRepository.findById(player.getId()).orElseThrow().getNpcVictories());
        assertEquals(4, items.findByPlayerIdAndItemKey(player.getId(), "rank_certificate_2").orElseThrow().getCount());
        for (int tier = 3; tier <= 17; tier++) {
            assertTrue(items.findByPlayerIdAndItemKey(player.getId(), "rank_certificate_" + tier).isEmpty());
        }
    }

    @Test
    void certificateCatalogUsesExactTierKeysAndShopSources() {
        Player player = createTestPlayer();
        for (int tier = 2; tier <= 17; tier++) {
            var rank = MilitaryRankDef.getRank(tier);
            player.setMilitaryRank(tier - 1); playerRepository.saveAndFlush(player);
            assertEquals(rank, MilitaryRankDef.certificateRank("rank_certificate_" + tier));
            assertTrue(rank.certificatePrice() > 0);
            assertEquals(MilitaryRankDef.certificateSource(rank),
                    ((Map<?, ?>) ranks.getRankInfo(player.getId()).get("certificate")).get("source"));
            assertTrue(ItemDef.ITEMS.get(rank.certificateKey()).desc().contains("商城"));
            assertFalse(ItemDef.ITEMS.get(rank.certificateKey()).desc().contains("掉率"));
            assertEquals(tier <= 7 ? 5 : tier <= 14 ? 8 : 10, RankExamDef.draw(tier).size());
        }
        for (String key : List.of("rank_certificate_1", "rank_certificate_18", "rank_certificate_02", "rank_certificate_x")) {
            assertNull(MilitaryRankDef.certificateRank(key));
        }
        assertNull(MilitaryRankDef.certificateRank(null));
    }

    @Test
    void mainQuestMilitaryDescriptionsIncludeAllFourConditions() {
        QuestCatalog.CHAPTERS.stream().flatMap(c -> c.quests().stream())
                .filter(q -> q.eventType().equals("MILITARY_RANK_TIER")).forEach(q -> {
                    var rank = MilitaryRankDef.getRank(q.targetValue());
                    assertTrue(q.desc().contains("声望" + rank.prestige() + "、"));
                    assertTrue(q.desc().contains(rank.certificateName()));
                    assertTrue(q.desc().contains("累计剿寇胜场" + rank.npcVictories() + "、"));
                    assertTrue(q.desc().contains("军事笔试"));
                    assertTrue(q.desc().contains(MilitaryRankDef.certificateSource(rank)));
                });
    }
}
