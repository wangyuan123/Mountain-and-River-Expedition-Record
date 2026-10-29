package com.wargame.service.quest;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.HistoricalOfficers;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import com.wargame.util.GameClock;

/**
 * 主线任务 + 阶段引导 服务。
 */
@Service
public class QuestService {

    /** 每日签到与 VIP 军饷共用游戏时钟的北京时间自然日，停服或服务器时区变化不改变刷新口径。 */
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");

    /** 军政签到五类资源各1万，预览与实际发放共用配置；道具另行保留。 */
    private static final Map<String, Integer> DAILY_CHECKIN_RESOURCES = Map.of(
            "food", 10000, "steel", 10000, "oil", 10000, "rare", 10000, "gold", 10000);

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private OnboardingService onboarding;

    private static final Logger log = LoggerFactory.getLogger(QuestService.class);

    private final PlayerQuestRepository playerQuestRepository;
    private final PlayerQuestStageRepository playerQuestStageRepository;
    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final PlayerRepository playerRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final OfficerRepository officerRepository;
    private final TechnologyRepository technologyRepository;
    private final PlayerCityRepository playerCityRepository;
    private final ForumTopicRepository forumTopicRepository;

    public QuestService(PlayerQuestRepository playerQuestRepository,
                        PlayerQuestStageRepository playerQuestStageRepository,
                        ResourcesRepository resourcesRepository,
                        PlayerItemRepository playerItemRepository,
                        PlayerRepository playerRepository,
                        BuildingRepository buildingRepository,
                        ArmyUnitRepository armyUnitRepository,
                        OfficerRepository officerRepository,
                        TechnologyRepository technologyRepository,
                        PlayerCityRepository playerCityRepository,
                        ForumTopicRepository forumTopicRepository) {
        this.playerQuestRepository = playerQuestRepository;
        this.playerQuestStageRepository = playerQuestStageRepository;
        this.resourcesRepository = resourcesRepository;
        this.playerItemRepository = playerItemRepository;
        this.playerRepository = playerRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.officerRepository = officerRepository;
        this.technologyRepository = technologyRepository;
        this.playerCityRepository = playerCityRepository;
        this.forumTopicRepository = forumTopicRepository;
    }

    // ====================================================================
    //  Event hook (called by other services)
    // ====================================================================

    @Transactional
    public void onEvent(Long playerId, String eventType, String targetKey, int delta) {
        if (playerId == null || eventType == null) return;
        if (delta <= 0) delta = 1;
        onboarding.onEvent(playerId, eventType, targetKey, delta);

        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            for (QuestCatalog.Quest q : chapter.quests()) {
                if (!q.eventType().equals(eventType)) continue;
                if (q.targetKey() != null && !q.targetKey().equals(targetKey)) continue;
                handleEventForQuest(playerId, q, delta);
            }
        }
    }

    @Transactional
    public void onEvent(Long playerId, String eventType) {
        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            for (QuestCatalog.Quest q : chapter.quests()) {
                if (q.eventType().equals(eventType)) {
                    int current = computeCurrent(playerId, q);
                    updateProgress(playerId, q, current);
                }
            }
        }
    }

    /** 快照式计算玩家当前实际达到的各指标数值。 */
    private int computeCurrent(Long playerId, QuestCatalog.Quest q) {
        return switch (q.eventType()) {
            case "ARMY_TOTAL" -> {
                if (q.targetKey() != null) {
                    yield armyUnitRepository.findByPlayerId(playerId).stream()
                            .filter(u -> q.targetKey().equals(u.getType()))
                            .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
                }
                yield armyUnitRepository.findByPlayerId(playerId).stream()
                        .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
            }
            case "ARMY_NAVY_TOTAL" -> {
                Set<String> navyKeys = Set.of("destroyer", "sub", "battleship", "carrier");
                yield armyUnitRepository.findByPlayerId(playerId).stream()
                        .filter(u -> navyKeys.contains(u.getType()))
                        .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
            }
            case "ARMY_AIR_TOTAL" -> {
                Set<String> airKeys = Set.of("scout", "fighter", "bomber", "transport");
                yield armyUnitRepository.findByPlayerId(playerId).stream()
                        .filter(u -> airKeys.contains(u.getType()))
                        .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
            }
            case "ARMY_ARMOR_TOTAL" -> {
                Set<String> armorKeys = Set.of("armored", "recon_car", "ltank", "htank", "assault", "rocket");
                yield armyUnitRepository.findByPlayerId(playerId).stream()
                        .filter(u -> armorKeys.contains(u.getType()))
                        .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
            }
            case "BUILD_UPGRADE_DONE" -> {
                if (q.targetKey() != null) {
                    List<Building> bs = buildingRepository.findByPlayerIdAndType(playerId, q.targetKey());
                    yield bs.stream().mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0).max().orElse(0);
                }
                yield 0;
            }
            case "BUILD_ALL_MAX" -> {
                // 检查主城核心建筑是否均已达到 20 级满级
                List<String> coreKeys = List.of("command", "house", "farm", "refinery", "oilfield", "raremine", "factory", "airport", "port", "lab", "wall");
                boolean allMax = true;
                for (String bk : coreKeys) {
                    int maxLv = buildingRepository.findByPlayerIdAndType(playerId, bk).stream()
                            .mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0).max().orElse(0);
                    if (maxLv < 20) {
                        allMax = false;
                        break;
                    }
                }
                yield allMax ? 1 : 0;
            }
            case "TECH_LEVEL" -> {
                if (q.targetKey() != null) {
                    yield technologyRepository.findByPlayerIdAndType(playerId, q.targetKey()).stream()
                            .mapToInt(t -> t.getLevel() != null ? t.getLevel() : 0).max().orElse(0);
                }
                yield 0;
            }
            case "TECH_LEVEL_COUNT" -> {
                // 统计等级 >= 8 的科技数量
                yield (int) technologyRepository.findByPlayerId(playerId).stream()
                        .filter(t -> t.getLevel() != null && t.getLevel() >= 8).count();
            }
            case "TECH_MAX_COUNT" -> {
                // 按各科技自身的5级或10级上限统计满级项数。
                yield (int) technologyRepository.findByPlayerId(playerId).stream()
                        .filter(t -> {
                            TechDef def = TechDef.TECHS.get(t.getType());
                            int max = def != null ? def.maxLevel() : 10;
                            return t.getLevel() != null && t.getLevel() >= max;
                        }).count();
            }
            case "TECH_ALL_MAX" -> {
                // 按当前科技目录统计已满级种类，新增与拆分科技也必须研发完成。
                int maxed = 0;
                for (TechDef def : TechDef.TECHS.values()) {
                    int lv = technologyRepository.findByPlayerIdAndType(playerId, def.key()).stream()
                            .mapToInt(t -> t.getLevel() != null ? t.getLevel() : 0).max().orElse(0);
                    if (lv >= def.maxLevel()) maxed++;
                }
                yield maxed;
            }
            case "PRESTIGE_VALUE" -> {
                Player p = playerRepository.findById(playerId).orElse(null);
                yield p != null && p.getPrestige() != null ? p.getPrestige() : 0;
            }
            case "MILITARY_RANK_TIER" -> {
                Player p = playerRepository.findById(playerId).orElse(null);
                yield p != null && p.getMilitaryRank() != null ? p.getMilitaryRank() : 1;
            }
            case "CITY_COUNT" -> {
                yield playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId).size();
            }
            case "HISTORICAL_OFFICER" -> {
                List<Officer> os = officerRepository.findByPlayerId(playerId);
                Set<String> historicalNames = new HashSet<>();
                for (HistoricalOfficers.Officer h : GameData.HISTORICAL_OFFICERS) {
                    historicalNames.add(h.name());
                }
                boolean hasHist = os.stream().anyMatch(o ->
                        (o.getStar() != null && o.getStar() >= 5) || historicalNames.contains(o.getName()));
                yield hasHist ? 1 : 0;
            }
            case "DISPLAY_NAME_CHANGE" -> {
                Player p = playerRepository.findById(playerId).orElse(null);
                yield (p != null && (p.getCommanderRenameCount() > 0 || (p.getDisplayName() != null && !p.getDisplayName().isBlank()
                        && !p.getDisplayName().equals(p.getUsername())
                        && !p.getDisplayName().matches("^远征统帅_\\d{6}$")))) ? 1 : 0;
            }
            case "CITY_NAME_CHANGE" -> {
                Player p = playerRepository.findById(playerId).orElse(null);
                yield (p != null && p.getCityNameRenamedAt() > 0) ? 1 : 0;
            }
            case "OFFICER_RENAME" -> {
                List<Officer> os = officerRepository.findByPlayerId(playerId);
                yield os.stream().anyMatch(o -> o.getNameRenamedAt() > 0) ? 1 : 0;
            }
            case "FORUM_POST" -> {
                yield forumTopicRepository.countByAuthorId(playerId) > 0 ? 1 : 0;
            }
            default -> 0;
        };
    }

    private void handleEventForQuest(Long playerId, QuestCatalog.Quest q, int delta) {
        PlayerQuest pq = ensureRow(playerId, q);
        if ("claimed".equals(pq.getStatus())) return;
        int target = q.targetValue();
        int newProgress = pq.getProgress() + delta;
        if (newProgress > target) newProgress = target;
        pq.setProgress(newProgress);
        if (newProgress >= target && !"completed".equals(pq.getStatus()) && !"claimed".equals(pq.getStatus())) {
            pq.setStatus("completed");
            pq.setCompletedAt(System.currentTimeMillis());
            log.info("Quest completed: player={} quest={}", playerId, q.id());
        }
        playerQuestRepository.save(pq);
        checkAndRefreshStageStatus(playerId, q.chapterId());
    }

    private void updateProgress(Long playerId, QuestCatalog.Quest q, int currentValue) {
        PlayerQuest pq = ensureRow(playerId, q);
        if ("claimed".equals(pq.getStatus())) return;
        int target = q.targetValue();
        pq.setProgress(Math.min(currentValue, target));
        if (currentValue >= target && !"completed".equals(pq.getStatus())) {
            pq.setStatus("completed");
            pq.setCompletedAt(System.currentTimeMillis());
            log.info("Quest completed by snapshot: player={} quest={}", playerId, q.id());
        }
        playerQuestRepository.save(pq);
        checkAndRefreshStageStatus(playerId, q.chapterId());
    }

    private PlayerQuest ensureRow(Long playerId, QuestCatalog.Quest q) {
        return playerQuestRepository.findByPlayerIdAndQuestId(playerId, q.id())
                .orElseGet(() -> {
                    PlayerQuest pq = new PlayerQuest();
                    pq.setPlayerId(playerId);
                    pq.setQuestId(q.id());
                    pq.setStatus("in_progress");
                    pq.setProgress(0);
                    pq.setTargetValue(q.targetValue());
                    return playerQuestRepository.save(pq);
                });
    }

    private PlayerQuestStage ensureStageRow(Long playerId, String stageId) {
        return playerQuestStageRepository.findByPlayerIdAndStageId(playerId, stageId)
                .orElseGet(() -> {
                    PlayerQuestStage s = new PlayerQuestStage();
                    s.setPlayerId(playerId);
                    s.setStageId(stageId);
                    // 默认阶段1初始为 in_progress，后续阶段初始为 locked
                    s.setStatus("stage1".equals(stageId) ? "in_progress" : "locked");
                    return playerQuestStageRepository.save(s);
                });
    }

    /** 检查某阶段是否所有任务均已完成，若是则将阶段标记为 completed (可领取大奖) */
    private void checkAndRefreshStageStatus(Long playerId, String stageId) {
        QuestCatalog.Chapter ch = QuestCatalog.findChapter(stageId);
        if (ch == null) return;
        PlayerQuestStage stageRow = ensureStageRow(playerId, stageId);
        if ("claimed".equals(stageRow.getStatus())) return;

        boolean allDone = true;
        for (QuestCatalog.Quest q : ch.quests()) {
            PlayerQuest pq = playerQuestRepository.findByPlayerIdAndQuestId(playerId, q.id()).orElse(null);
            if (pq == null || (!"completed".equals(pq.getStatus()) && !"claimed".equals(pq.getStatus()))) {
                allDone = false;
                break;
            }
        }
        if (allDone && !"completed".equals(stageRow.getStatus())) {
            stageRow.setStatus("completed");
            stageRow.setCompletedAt(System.currentTimeMillis());
            playerQuestStageRepository.save(stageRow);
            log.info("Quest stage completed: player={} stage={}", playerId, stageId);
        }
    }

    // ====================================================================
    //  Claim rewards
    // ====================================================================

    @Transactional
    public Map<String, Object> claim(Long playerId, String questId) {
        Map<String, Object> result = new LinkedHashMap<>();
        QuestCatalog.Quest q = QuestCatalog.findQuest(questId);
        if (q == null) {
            result.put("success", false);
            result.put("message", "任务不存在: " + questId);
            return result;
        }
        PlayerQuest pq = playerQuestRepository.findByPlayerIdAndQuestId(playerId, questId).orElse(null);
        if (pq == null) {
            result.put("success", false);
            result.put("message", "请先接取该任务");
            return result;
        }
        if ("claimed".equals(pq.getStatus())) {
            result.put("success", false);
            result.put("message", "该任务奖励已领取");
            return result;
        }
        if (!"completed".equals(pq.getStatus())) {
            result.put("success", false);
            result.put("message", "任务尚未完成 (进度 " + pq.getProgress() + "/" + pq.getTargetValue() + ")");
            return result;
        }

        grantReward(playerId, q.reward());

        pq.setStatus("claimed");
        pq.setClaimedAt(System.currentTimeMillis());
        playerQuestRepository.save(pq);

        checkAndRefreshStageStatus(playerId, q.chapterId());

        result.put("success", true);
        result.put("message", "已领取【" + q.title() + "】的任务奖励");
        result.put("reward", q.reward());
        result.put("questId", q.id());
        return result;
    }

    /** 领取阶段通关豪华大礼包并解锁下一阶段。 */
    @Transactional
    public Map<String, Object> claimStage(Long playerId, String stageId) {
        playerRepository.lockById(playerId).orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        QuestCatalog.Chapter chapter = QuestCatalog.findChapter(stageId);
        if (chapter == null) {
            result.put("success", false);
            result.put("message", "阶段不存在: " + stageId);
            return result;
        }

        PlayerQuestStage stageRow = ensureStageRow(playerId, stageId);
        if ("claimed".equals(stageRow.getStatus())) {
            result.put("success", false);
            result.put("message", "该阶段大奖已领取");
            return result;
        }

        // 已完成章节是历史成果，迁入新任务不能撤销旧的领奖资格。
        for (QuestCatalog.Quest q : "completed".equals(stageRow.getStatus()) ? List.<QuestCatalog.Quest>of() : chapter.quests()) {
            PlayerQuest pq = playerQuestRepository.findByPlayerIdAndQuestId(playerId, q.id()).orElse(null);
            if (pq == null || (!"completed".equals(pq.getStatus()) && !"claimed".equals(pq.getStatus()))) {
                result.put("success", false);
                result.put("message", "请先完成本阶段的所有任务再领取阶段大礼包");
                return result;
            }
        }

        // 发放阶段通关大礼包至玩家仓库背包
        String giftKey = chapter.giftKey();
        addItem(playerId, giftKey, 1);

        stageRow.setStatus("claimed");
        stageRow.setClaimedAt(System.currentTimeMillis());
        playerQuestStageRepository.save(stageRow);

        // 解锁下一阶段
        String nextStageId = QuestCatalog.nextChapterId(stageId);
        if (nextStageId != null) {
            PlayerQuestStage nextStageRow = ensureStageRow(playerId, nextStageId);
            if (!"claimed".equals(nextStageRow.getStatus()) && !"completed".equals(nextStageRow.getStatus())) {
                nextStageRow.setStatus("in_progress");
                playerQuestStageRepository.save(nextStageRow);
            }
            QuestCatalog.Chapter nextCh = QuestCatalog.findChapter(nextStageId);
            if (nextCh != null) {
                for (QuestCatalog.Quest nq : nextCh.quests()) {
                    ensureRow(playerId, nq);
                }
            }
        }

        result.put("success", true);
        result.put("message", "恭喜通关【" + chapter.name() + "】！豪华大礼【" + chapter.giftName() + "】已发放到您的仓库背包，前往仓库开启即可获得海量资源、黄金与钻石！");
        result.put("stageId", stageId);
        result.put("giftKey", giftKey);
        result.put("giftName", chapter.giftName());
        result.put("nextStageId", nextStageId);
        return result;
    }

    private void grantReward(Long playerId, QuestCatalog.Reward r) {
        if (r == null) return;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res != null) {
            res.addResource("food", r.food());
            res.addResource("steel", r.steel());
            res.addResource("oil", r.oil());
            res.addResource("rare", r.rare());
            res.addResource("gold", r.gold());
            resourcesRepository.save(res);
        }
        if (r.skillBook() != null && !r.skillBook().isBlank()) {
            addItem(playerId, "skillBook", parseInt(r.skillBook(), 1));
        }
        if (r.expBook() != null && !r.expBook().isBlank()) {
            addItem(playerId, "expBook", parseInt(r.expBook(), 1));
        }
        if (r.itemKey() != null && !r.itemKey().isBlank() && r.itemCount() > 0) {
            addItem(playerId, r.itemKey(), r.itemCount());
        }
    }

    private void addItem(Long playerId, String itemKey, int count) {
        if (count <= 0) return;
        PlayerItem existing = playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey).orElse(null);
        if (existing == null) {
            PlayerItem pi = new PlayerItem();
            pi.setPlayerId(playerId);
            pi.setItemKey(itemKey);
            pi.setCount(count);
            pi.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(pi);
        } else {
            existing.setCount((existing.getCount() != null ? existing.getCount() : 0) + count);
            existing.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(existing);
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ====================================================================
    //  Query state
    // ====================================================================

    @Transactional
    public List<Map<String, Object>> getPlayerQuests(Long playerId) {
        // 确保阶段1已初始化
        ensureStageRow(playerId, "stage1");

        // 阶段锁状态流动计算
        String unlockedStageId = "stage1";
        for (int i = 0; i < QuestCatalog.CHAPTERS.size(); i++) {
            QuestCatalog.Chapter ch = QuestCatalog.CHAPTERS.get(i);
            PlayerQuestStage s = ensureStageRow(playerId, ch.id());
            if ("claimed".equals(s.getStatus())) {
                if (i + 1 < QuestCatalog.CHAPTERS.size()) {
                    unlockedStageId = QuestCatalog.CHAPTERS.get(i + 1).id();
                }
            } else {
                if (unlockedStageId.equals(ch.id()) && !"completed".equals(s.getStatus())) {
                    if (!"in_progress".equals(s.getStatus())) {
                        s.setStatus("in_progress");
                        playerQuestStageRepository.save(s);
                    }
                }
                break;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        boolean previousStageClaimed = true;

        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            PlayerQuestStage stageRow = ensureStageRow(playerId, chapter.id());
            String stageStatus = stageRow.getStatus();

            // 若前序阶段未 claimed，当前阶段为 locked
            if (!previousStageClaimed && !"claimed".equals(stageStatus) && !"completed".equals(stageStatus)) {
                stageStatus = "locked";
            }

            Map<String, Object> chMap = new LinkedHashMap<>();
            chMap.put("id", chapter.id());
            chMap.put("name", chapter.name());
            chMap.put("intro", chapter.intro());
            chMap.put("giftKey", chapter.giftKey());
            chMap.put("giftName", chapter.giftName());

            List<Map<String, Object>> quests = new ArrayList<>();
            int completedCount = 0;
            int claimedCount = 0;

            for (QuestCatalog.Quest q : chapter.quests()) {
                PlayerQuest pq = ensureRow(playerId, q);

                // 若非 locked 且未 claimed，执行快照同步检测
                if (!"locked".equals(stageStatus) && !"claimed".equals(pq.getStatus())) {
                    int snapshotVal = computeCurrent(playerId, q);
                    if (snapshotVal > pq.getProgress()) {
                        pq.setProgress(Math.min(snapshotVal, q.targetValue()));
                    }
                    if (pq.getProgress() >= q.targetValue() && !"completed".equals(pq.getStatus())) {
                        pq.setStatus("completed");
                        pq.setCompletedAt(System.currentTimeMillis());
                    }
                    playerQuestRepository.save(pq);
                }

                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("id", q.id());
                qm.put("title", q.title());
                qm.put("desc", q.desc());
                qm.put("target", q.targetValue());
                qm.put("reward", q.reward());

                String qStatus = "locked".equals(stageStatus) ? "locked" : pq.getStatus();
                qm.put("status", qStatus);
                qm.put("progress", pq.getProgress());
                qm.put("completedAt", pq.getCompletedAt());
                qm.put("claimedAt", pq.getClaimedAt());
                quests.add(qm);

                if ("completed".equals(pq.getStatus()) || "claimed".equals(pq.getStatus())) {
                    completedCount++;
                }
                if ("claimed".equals(pq.getStatus())) {
                    claimedCount++;
                }
            }

            // 如果该阶段所有任务均已达成，且当前阶段不是 claimed，更新为 completed
            if (!"locked".equals(stageStatus) && !"claimed".equals(stageStatus)) {
                if (completedCount == chapter.quests().size()) {
                    stageStatus = "completed";
                    if (!"completed".equals(stageRow.getStatus())) {
                        stageRow.setStatus("completed");
                        stageRow.setCompletedAt(System.currentTimeMillis());
                        playerQuestStageRepository.save(stageRow);
                    }
                } else if (!"completed".equals(stageRow.getStatus())) {
                    stageStatus = "in_progress";
                }
            }

            chMap.put("status", stageStatus);
            chMap.put("canClaimStage", "completed".equals(stageStatus));
            chMap.put("completedCount", completedCount);
            chMap.put("claimedCount", claimedCount);
            chMap.put("totalCount", chapter.quests().size());
            chMap.put("quests", quests);
            out.add(chMap);

            previousStageClaimed = "claimed".equals(stageStatus);
        }

        return out;
    }

    public Map<String, Object> getGuide(Long playerId) { return onboarding.status(playerId); }
    public Map<String, Object> advanceGuide(Long playerId) { return onboarding.status(playerId); }
    public Map<String, Object> skipGuide(Long playerId) { return onboarding.pause(playerId, true); }

    // ====================================================================
    //  Daily Check-in (每日军政点卯签到)
    // ====================================================================

    public Map<String, Object> getDailyCheckinStatus(Long playerId) {
        Player player = playerRepository.findById(playerId).orElse(null);
        LocalDate today = gameDate();
        boolean claimed = player != null && player.getLastDailyCheckinDate() != null
                && player.getLastDailyCheckinDate().equals(today);

        Map<String, Object> checkin = new LinkedHashMap<>();
        checkin.put("date", today.toString());
        checkin.put("claimed", claimed);
        Map<String, Integer> rewards = new LinkedHashMap<>();
        rewards.put("renameCard", 1);
        rewards.put("cityRenameCard", 1);
        rewards.putAll(DAILY_CHECKIN_RESOURCES);
        rewards.put("speedUp10m", 1);
        checkin.put("rewards", rewards);
        return checkin;
    }

    @Transactional
    public Map<String, Object> claimDailyCheckin(Long playerId) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: " + playerId));

        LocalDate today = gameDate();
        if (player.getLastDailyCheckinDate() != null && player.getLastDailyCheckinDate().equals(today)) {
            throw new IllegalArgumentException("今日军政签到奖励已领受，每日 00:00 刷新");
        }

        player.setLastDailyCheckinDate(today);
        playerRepository.save(player);

        // 发放改名卡与战备道具
        addItem(playerId, "renameCard", 1);
        addItem(playerId, "cityRenameCard", 1);
        addItem(playerId, "speedUp10m", 1);

        // 发放常备资源至活动城池
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res != null) {
            DAILY_CHECKIN_RESOURCES.forEach(res::addResource);
            resourcesRepository.save(res);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "每日军务签到成功，已领受粮钢油稀金各10000及改名卡、加速道具！");
        result.put("dailyCheckin", getDailyCheckinStatus(playerId));
        return result;
    }

    /** 当前游戏时间对应的北京时间自然日。 */
    private LocalDate gameDate() {
        return Instant.ofEpochMilli(GameClock.now()).atZone(GAME_ZONE).toLocalDate();
    }
}
