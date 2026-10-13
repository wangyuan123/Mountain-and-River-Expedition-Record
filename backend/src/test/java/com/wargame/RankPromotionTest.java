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

    @Test
    void missingPrerequisitesAndExamPreventPromotionWithoutConsumingItems() {
        Player p = createTestPlayer();
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(p.getId()));
        p.setPrestige(200); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.startExam(p.getId()));
        p.setNpcVictories(3); playerRepository.saveAndFlush(p);
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
        items.saveAndFlush(new PlayerItem(null, p.getId(), "gem_pearl", 50, 0L));
        var grade = pass(p.getId(), 1);
        assertEquals(true, grade.get("passed"));
        assertEquals(4, grade.get("correct"));
        assertTrue(playerQuests.findByPlayerIdAndQuestId(p.getId(), "q1_9").isEmpty(), "仅通过笔试不应完成授衔任务");
        String attempt = stored(p.getId()).attemptId();
        assertThrows(IllegalStateException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission(attempt, List.of(0, 0, 0, 0, 0))));
        ranks.promoteRank(p.getId());
        Player promoted = playerRepository.findById(p.getId()).orElseThrow();
        assertEquals(2, promoted.getMilitaryRank());
        assertEquals(200, promoted.getPrestige());
        assertEquals(3, promoted.getNpcVictories());
        assertNull(promoted.getRankExam());
        assertEquals(1, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_2").orElseThrow().getCount());
        assertEquals(9, items.findByPlayerIdAndItemKey(p.getId(), "rank_certificate_3").orElseThrow().getCount());
        assertEquals(50, items.findByPlayerIdAndItemKey(p.getId(), "gem_pearl").orElseThrow().getCount());
        assertEquals(2, playerQuests.findByPlayerIdAndQuestId(p.getId(), "q1_9").orElseThrow().getProgress());
        assertThrows(IllegalArgumentException.class, () -> ranks.submitExam(p.getId(), new RankService.ExamSubmission(attempt, List.of())));
    }

    @Test
    void promotionRechecksPrestigeVictoriesAndInventoryAfterPassingExam() throws Exception {
        Player p = eligible(1);
        pass(p.getId(), 0);
        p = playerRepository.findById(p.getId()).orElseThrow();
        Long id = p.getId();
        p.setPrestige(199); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        p.setPrestige(200); p.setNpcVictories(2); playerRepository.saveAndFlush(p);
        assertThrows(IllegalArgumentException.class, () -> ranks.promoteRank(id));
        p.setNpcVictories(3); playerRepository.saveAndFlush(p);
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
        p.setPrestige(15000); p.setNpcVictories(130); playerRepository.saveAndFlush(p);
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
    void allNpcLevelsMapToOneExactCertificateAndProbabilityBoundaryIsExclusive() {
        int lastTier = 2;
        for (int level = 1; level <= 30; level++) {
            var rank = MilitaryRankDef.certificateRankForNpc(level);
            assertTrue(rank.tier() >= lastTier); lastTier = rank.tier();
            assertEquals(rank.certificateKey(), MilitaryRankDef.rollPromotionCertificate(level, 0));
            assertNull(MilitaryRankDef.rollPromotionCertificate(level, rank.certificateDropPercent() / 100.0));
            assertEquals(rank.certificateKey(), MilitaryRankDef.rollPromotionCertificate(level, rank.certificateDropPercent() / 100.0 - 0.00001));
            assertNotNull(ItemDef.ITEMS.get(rank.certificateKey()));
        }
        assertNull(MilitaryRankDef.rollPromotionCertificate(0, 0));
        assertNull(MilitaryRankDef.rollPromotionCertificate(31, 0));
        assertThrows(IllegalArgumentException.class, () -> MilitaryRankDef.rollPromotionCertificate(1, Double.NaN));
        assertEquals(17, lastTier);
        for (int tier = 2; tier <= 17; tier++) {
            var rank = MilitaryRankDef.getRank(tier);
            assertEquals(tier, MilitaryRankDef.certificateRankForNpc(rank.minNpcLevel()).tier());
            assertEquals(tier, MilitaryRankDef.certificateRankForNpc(MilitaryRankDef.maxNpcLevelForCertificate(tier)).tier());
            assertEquals(tier <= 7 ? 5 : tier <= 14 ? 8 : 10, RankExamDef.draw(tier).size());
        }
    }

    @Test
    void mainQuestMilitaryDescriptionsIncludeAllFourConditions() {
        QuestCatalog.CHAPTERS.stream().flatMap(c -> c.quests().stream())
                .filter(q -> q.eventType().equals("MILITARY_RANK_TIER")).forEach(q -> {
                    var rank = MilitaryRankDef.getRank(q.targetValue());
                    assertTrue(q.desc().contains(rank.certificateName()));
                    assertTrue(q.desc().contains("累计剿寇胜场"));
                    assertTrue(q.desc().contains("军事笔试"));
                });
    }
}
