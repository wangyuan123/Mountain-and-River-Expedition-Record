package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.RankExamDef;
import com.wargame.model.entity.*;
import com.wargame.model.dto.BattleResult;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.quest.QuestService;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class RankService {
    private final PlayerRepository playerRepository;
    private final PlayerItemRepository playerItemRepository;
    private final AccountService accounts;
    private final QuestService quests;
    private final WebSocketPushService pushService;

    public record Exam(String attemptId, int tier, long expiresAt, String status, long retryAt,
                       int correct, List<RankExamDef.Question> questions) {}
    public record ExamSubmission(String attemptId, List<Integer> answers) {}

    private int tier(Player player) { return Objects.requireNonNullElse(player.getMilitaryRank(), 1); }
    private int prestige(Player player) { return Objects.requireNonNullElse(player.getPrestige(), 0); }
    private int owned(Long id, String key) {
        return playerItemRepository.findByPlayerIdAndItemKey(id, key).map(PlayerItem::getCount).orElse(0);
    }
    private Exam exam(Player player) {
        if (player.getRankExam() == null) return null;
        try { return JsonUtil.getMapper().readValue(player.getRankExam(), Exam.class); }
        catch (Exception e) { throw new IllegalStateException("考试记录读取失败", e); }
    }
    private Exam currentExam(Player player) {
        Exam exam = exam(player);
        return exam != null && exam.tier() == tier(player) + 1 ? exam : null;
    }
    private String status(Exam exam, long now) {
        if (exam == null) return "not_started";
        return "active".equals(exam.status()) && now >= exam.expiresAt() ? "expired" : exam.status();
    }
    private long retryAt(Exam exam, long now) {
        return exam != null && "expired".equals(status(exam, now))
                ? exam.expiresAt() + RankExamDef.COOLDOWN_MS : exam == null ? 0 : exam.retryAt();
    }

    /** 返回四项晋升条件；标准答案与完整考试快照不进入游戏状态。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getRankInfo(Long playerId) {
        Player player = playerRepository.findById(playerId).orElseThrow();
        int tier = tier(player);
        var rank = MilitaryRankDef.getRank(tier);
        var expansion = MilitaryRankDef.nextCityRank(tier);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tier", tier); info.put("name", rank.name()); info.put("baseCap", rank.baseCap());
        info.put("prestige", prestige(player)); info.put("npcVictories", player.getNpcVictories());
        info.put("cityCap", MilitaryRankDef.getCityCap(tier));
        info.put("nextCityCap", MilitaryRankDef.getCityCap(tier + 1));
        info.put("nextCityRankName", expansion == null ? null : expansion.name());
        info.put("nextExpansionCap", MilitaryRankDef.getCityCap(expansion == null ? tier : expansion.tier()));
        boolean max = tier >= MilitaryRankDef.MAX_RANK_TIER;
        info.put("isMax", max);
        info.put("canPromote", false); info.put("canTakeExam", false);
        if (max) return info;
        var next = MilitaryRankDef.getRank(tier + 1);
        boolean prestigeEnough = prestige(player) >= next.prestige();
        boolean victoriesEnough = player.getNpcVictories() >= next.npcVictories();
        int owned = owned(playerId, next.certificateKey());
        boolean prerequisites = prestigeEnough && victoriesEnough && owned > 0;
        long now = GameClock.now();
        info.put("serverTime", now);
        Exam exam = currentExam(player);
        String status = status(exam, now);
        boolean passed = "passed".equals(status);
        Map<String, Object> examInfo = new LinkedHashMap<>();
        examInfo.put("status", status); examInfo.put("questionCount", RankExamDef.questionCount(next.tier()));
        examInfo.put("requiredCorrect", RankExamDef.requiredCorrect(next.tier()));
        examInfo.put("expiresAt", exam == null ? 0 : exam.expiresAt());
        examInfo.put("retryAt", retryAt(exam, now)); examInfo.put("correct", exam == null ? 0 : exam.correct());
        info.put("nextTier", next.tier()); info.put("nextName", next.name()); info.put("nextBaseCap", next.baseCap());
        info.put("reqPrestige", next.prestige()); info.put("reqNpcVictories", next.npcVictories());
        info.put("certificate", Map.of("key", next.certificateKey(), "name", next.certificateName(), "owned", owned,
                "required", 1, "source", MilitaryRankDef.certificateSource(next)));
        info.put("prestigeEnough", prestigeEnough); info.put("victoriesEnough", victoriesEnough);
        info.put("certificateEnough", owned > 0); info.put("prerequisitesMet", prerequisites);
        info.put("exam", examInfo); info.put("examPassed", passed);
        info.put("canPromote", prerequisites && passed);
        info.put("canTakeExam", prerequisites && !passed && now >= retryAt(exam, now));
        return info;
    }

    private MilitaryRankDef.RankInfo requirePrerequisites(Player player) {
        if (tier(player) >= MilitaryRankDef.MAX_RANK_TIER) throw new IllegalStateException("已达到最高军衔");
        var next = MilitaryRankDef.getRank(tier(player) + 1);
        if (prestige(player) < next.prestige()) throw new IllegalArgumentException("声望不足，需要 " + next.prestige());
        if (player.getNpcVictories() < next.npcVictories()) throw new IllegalArgumentException("累计剿寇胜场不足，需要 " + next.npcVictories() + " 场");
        if (owned(player.getId(), next.certificateKey()) < 1) throw new IllegalArgumentException("缺少【" + next.certificateName() + "】");
        return next;
    }

    /** 开考或恢复未过期试卷；不扣凭证，刷新页面不能更换试卷或延长时间。 */
    @Transactional
    public Map<String, Object> startExam(Long playerId) {
        Player player = accounts.lockPlayer(playerId);
        var next = requirePrerequisites(player);
        long now = GameClock.now();
        Exam exam = currentExam(player);
        if ("passed".equals(status(exam, now))) throw new IllegalStateException("本阶笔试已合格，请授衔晋升");
        if (now < retryAt(exam, now)) throw new IllegalStateException("考试冷却中，请稍后重试");
        if (!"active".equals(status(exam, now))) {
            exam = new Exam(UUID.randomUUID().toString(), next.tier(), now + RankExamDef.DURATION_MS,
                    "active", 0, 0, RankExamDef.draw(next.tier()));
            player.setRankExam(JsonUtil.toJson(exam));
            playerRepository.save(player);
        }
        Map<String, Object> paper = new LinkedHashMap<>();
        paper.put("attemptId", exam.attemptId()); paper.put("tier", exam.tier()); paper.put("rankName", next.name());
        paper.put("expiresAt", exam.expiresAt()); paper.put("serverTime", now);
        paper.put("requiredCorrect", RankExamDef.requiredCorrect(exam.tier()));
        paper.put("questions", exam.questions().stream().map(q -> Map.of("id", q.id(), "prompt", q.prompt(), "options", q.options())).toList());
        return paper;
    }

    /** 单次交卷并由服务端判分，超时视为未通过；旧试卷不能用于下阶晋升。 */
    @Transactional
    public Map<String, Object> submitExam(Long playerId, ExamSubmission submission) {
        Player player = accounts.lockPlayer(playerId);
        Exam exam = currentExam(player);
        if (exam == null || submission == null || !Objects.equals(exam.attemptId(), submission.attemptId()))
            throw new IllegalArgumentException("试卷已失效，请重新进入考试");
        if (!"active".equals(exam.status())) throw new IllegalStateException("该试卷已提交");
        long now = GameClock.now();
        boolean expired = now >= exam.expiresAt();
        int correct = 0;
        if (!expired) {
            List<Integer> answers = submission.answers();
            if (answers == null || answers.size() != exam.questions().size()
                    || answers.stream().anyMatch(a -> a == null || a < 0 || a > 3))
                throw new IllegalArgumentException("请完成所有题目后交卷");
            for (int i = 0; i < answers.size(); i++) if (answers.get(i) == exam.questions().get(i).answer()) correct++;
        }
        boolean passed = !expired && correct >= RankExamDef.requiredCorrect(exam.tier());
        // 判分可独立完成；正式晋升仍重新核验声望、胜场和库存。
        Exam graded = new Exam(exam.attemptId(), exam.tier(), exam.expiresAt(), passed ? "passed" : expired ? "expired" : "failed",
                passed ? 0 : (expired ? exam.expiresAt() : now) + RankExamDef.COOLDOWN_MS, correct, exam.questions());
        player.setRankExam(JsonUtil.toJson(graded)); playerRepository.save(player);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed); result.put("correct", correct); result.put("total", exam.questions().size());
        result.put("requiredCorrect", RankExamDef.requiredCorrect(exam.tier())); result.put("retryAt", graded.retryAt());
        result.put("message", passed ? "军事笔试合格，可授衔晋升" : expired ? "考试已超时，请在冷却结束后重考" : "军事笔试未通过，请在冷却结束后重考");
        result.put("rankInfo", getRankInfo(playerId));
        return result;
    }

    /** 四项条件在账号锁内重新核验，凭证扣减与军衔升级在同一事务中完成。 */
    @Transactional
    public Map<String, Object> promoteRank(Long playerId) {
        Player player = accounts.lockPlayer(playerId);
        var next = requirePrerequisites(player);
        Exam exam = currentExam(player);
        if (exam == null || !"passed".equals(exam.status())) throw new IllegalArgumentException("请先通过本阶军事笔试");
        if (playerItemRepository.tryConsume(playerId, next.certificateKey(), 1, GameClock.now()) != 1)
            throw new IllegalArgumentException("晋升凭证不足");
        // 条件更新会清空持久化上下文，必须重新取得受锁保护的玩家实体。
        player = accounts.lockPlayer(playerId);
        player.setMilitaryRank(next.tier()); player.setRankExam(null); playerRepository.save(player);
        quests.onEvent(playerId, "MILITARY_RANK_TIER");
        pushService.pushToPlayer(playerId, "rank_promoted", Map.of("tier", next.tier(), "rankName", next.name()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("tier", next.tier()); result.put("rankName", next.name());
        result.put("cityCap", MilitaryRankDef.getCityCap(next.tier()));
        result.put("message", "晋升为【" + next.name() + "】，最多可拥有 " + MilitaryRankDef.getCityCap(next.tier()) + " 座城市");
        return result;
    }

    /** 仅真实 NPC 有守军的进攻胜利计一场；在行军结算事务内直接发放凭证至账号仓库。 */
    @Transactional
    public void awardNpcVictory(Long playerId, Object target, BattleResult result) {
        int level = target instanceof Bandit bandit ? Objects.requireNonNullElse(bandit.getLevel(), 0)
                : target instanceof NpcCity city ? Objects.requireNonNullElse(city.getLevel(), 0) : 0;
        if (!result.isWin() || level < 1 || result.getInitialDefender() == null
                || result.getInitialDefender().values().stream().noneMatch(n -> n != null && n > 0)) return;
        Player player = accounts.lockPlayer(playerId);
        player.setNpcVictories(player.getNpcVictories() + 1); playerRepository.save(player);
        String key = MilitaryRankDef.rollPromotionCertificate(level, ThreadLocalRandom.current().nextDouble());
        String report = Objects.requireNonNullElse(result.getReport(), "") + "\n★ 剿寇胜场 +1，累计 " + player.getNpcVictories() + " 场。\n";
        if (key != null) {
            PlayerItem item = playerItemRepository.findByPlayerIdAndItemKey(playerId, key)
                    .orElseGet(() -> new PlayerItem(null, playerId, key, 0, GameClock.now()));
            item.setCount(item.getCount() + 1); item.setUpdatedAt(GameClock.now()); playerItemRepository.save(item);
            report += "★ 获得【" + MilitaryRankDef.certificateRankForNpc(level).certificateName() + "】×1，已入仓库。\n";
        }
        result.setReport(report);
    }
}
