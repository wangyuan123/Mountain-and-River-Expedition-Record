package com.wargame.service.quest;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 主线任务 + 新手引导 服务。
 * <p>
 * 公共 API：
 * <ul>
 *   <li>{@link #onEvent} - 业务侧事件钩子入口</li>
 *   <li>{@link #getPlayerQuests} - 拉取某玩家所有任务当前状态</li>
 *   <li>{@link #claim} - 领取任务奖励</li>
 *   <li>{@link #getGuide} - 拉取新手引导当前步骤（含实时进度）</li>
 *   <li>{@link #advanceGuide} - 推进到下一步（仅在目标达成时通过）</li>
 *   <li>{@link #skipGuide} - 跳过整个引导</li>
 * </ul>
 */
@Service
public class QuestService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private OnboardingService onboarding;

    private static final Logger log = LoggerFactory.getLogger(QuestService.class);

    private final PlayerQuestRepository playerQuestRepository;
    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final PlayerRepository playerRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final OfficerRepository officerRepository;

    public QuestService(PlayerQuestRepository playerQuestRepository,
                        ResourcesRepository resourcesRepository,
                        PlayerItemRepository playerItemRepository,
                        PlayerRepository playerRepository,
                        BuildingRepository buildingRepository,
                        ArmyUnitRepository armyUnitRepository,
                        OfficerRepository officerRepository) {
        this.playerQuestRepository = playerQuestRepository;
        this.resourcesRepository = resourcesRepository;
        this.playerItemRepository = playerItemRepository;
        this.playerRepository = playerRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.officerRepository = officerRepository;
    }

    // ====================================================================
    //  Event hook (called by other services)
    // ====================================================================

    /**
     * 业务事件钩子。被其他 service 调用，例如：
     * <pre>
     *   questService.onEvent(playerId, "BUILD_UPGRADE_DONE", "farm", 1);
     * </pre>
     * 钩子会扫描所有需要此事件类型的任务，累加 progress，完成则标 completed。
     * 已 claimed / 超过 target 的任务不会再被处理。
     */
    @Transactional
    public void onEvent(Long playerId, String eventType, String targetKey, int delta) {
        if (playerId == null || eventType == null) return;
        if (delta <= 0) delta = 1;
        onboarding.onEvent(playerId, eventType, targetKey, delta);

        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            for (QuestCatalog.Quest q : chapter.quests()) {
                if (!q.eventType().equals(eventType)) continue;
                // targetKey 过滤 (e.g. 只匹配 infantry)
                if (q.targetKey() != null && !q.targetKey().equals(targetKey)) continue;
                handleEventForQuest(playerId, q, delta);
            }
        }
    }

    /** 复合事件：单次"扫描当前 player 数据"再判断（ARMY_TOTAL / BUILD_LEVEL_SUM / OFFICER_RECRUIT_STAR 等）。 */
    @Transactional
    public void onEvent(Long playerId, String eventType) {
        // 复合目标必须读取业务数据的当前快照，而不是把一次建造/招募当作 1 次进度。
        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            for (QuestCatalog.Quest q : chapter.quests()) {
                if (q.eventType().equals(eventType)) {
                    int current = computeCurrent(playerId, q);
                    updateProgress(playerId, q, current);
                }
            }
        }
    }

    private int computeCurrent(Long playerId, QuestCatalog.Quest q) {
        return switch (q.eventType()) {
            case "ARMY_TOTAL" -> armyUnitRepository.findByPlayerId(playerId).stream()
                    .mapToInt(u -> u.getCount() != null ? u.getCount() : 0).sum();
            case "BUILD_LEVEL_SUM" -> {
                List<Building> bs = buildingRepository.findByPlayerIdAndType(playerId, q.targetKey());
                yield bs.stream().mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0).sum();
            }
            case "BUILD_COUNT" -> buildingRepository.findByPlayerIdAndType(playerId, q.targetKey()).size();
            case "PLAYER_LEVEL" -> {
                Player p = playerRepository.findById(playerId).orElse(null);
                yield p != null && p.getLevel() != null ? p.getLevel() : 0;
            }
            case "SCOUT_COMPLETE", "GATHER_COMPLETE" -> 0;
            case "OFFICER_RECRUIT_STAR" -> {
                List<Officer> os = officerRepository.findByPlayerId(playerId);
                int maxStar = 0;
                for (Officer o : os) {
                    int s = o.getStar() != null ? o.getStar() : 0;
                    if (s > maxStar) maxStar = s;
                }
                yield maxStar;
            }
            default -> 0;
        };
    }

    private void handleEventForQuest(Long playerId, QuestCatalog.Quest q, int delta) {
        PlayerQuest pq = ensureRow(playerId, q);
        if ("claimed".equals(pq.getStatus()) || "locked".equals(pq.getStatus())) return;
        int target = q.targetValue();
        int newProgress;
        if (q.targetKey() == null) {
            // 无 targetKey 的任务直接 set 增量（OFFICER_RECRUIT 等）
            newProgress = pq.getProgress() + delta;
        } else {
            // 对 BUILD_UPGRADE_DONE / ARMY_RECRUIT 这种带 targetKey 的累加 delta
            newProgress = pq.getProgress() + delta;
        }
        if (newProgress > target) newProgress = target;
        pq.setProgress(newProgress);
        if (newProgress >= target && !"completed".equals(pq.getStatus()) && !"claimed".equals(pq.getStatus())) {
            pq.setStatus("completed");
            pq.setCompletedAt(System.currentTimeMillis());
            log.info("Quest completed: player={} quest={}", playerId, q.id());
            // 自动解锁下一任务
            String nextId = QuestCatalog.nextQuestInChapter(q.id());
            if (nextId != null) {
                QuestCatalog.Quest next = QuestCatalog.findQuest(nextId);
                if (next != null) ensureRow(playerId, next);
            }
        }
        playerQuestRepository.save(pq);
    }

    private void updateProgress(Long playerId, QuestCatalog.Quest q, int currentValue) {
        PlayerQuest pq = ensureRow(playerId, q);
        if ("claimed".equals(pq.getStatus())) return;
        int target = q.targetValue();
        pq.setProgress(Math.min(currentValue, target));
        if (currentValue >= target && !"completed".equals(pq.getStatus())) {
            pq.setStatus("completed");
            pq.setCompletedAt(System.currentTimeMillis());
            String nextId = QuestCatalog.nextQuestInChapter(q.id());
            if (nextId != null) {
                QuestCatalog.Quest next = QuestCatalog.findQuest(nextId);
                if (next != null) ensureRow(playerId, next);
            }
        }
        playerQuestRepository.save(pq);
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

    // ====================================================================
    //  Claim reward
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
            result.put("message", "奖励已领取");
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

        result.put("success", true);
        result.put("message", "已领取 " + q.title() + " 的奖励");
        result.put("reward", q.reward());
        result.put("questId", q.id());
        return result;
    }

    /** 派发奖励 (资源 + 道具) - 提为独立方法以便引导步骤复用。 */
    private void grantReward(Long playerId, QuestCatalog.Reward r) {
        if (r == null) return;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res != null) {
            res.setFood((res.getFood() != null ? res.getFood() : 0) + r.food());
            res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + r.steel());
            res.setOil((res.getOil() != null ? res.getOil() : 0) + r.oil());
            res.setRare((res.getRare() != null ? res.getRare() : 0) + r.rare());
            res.setGold((res.getGold() != null ? res.getGold() : 0) + r.gold());
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

    /** 拉取某玩家所有任务当前状态 (初始化时确保每个任务都有 row)。 */
    @Transactional
    public List<Map<String, Object>> getPlayerQuests(Long playerId) {
        // 懒初始化：首次访问时为该玩家创建所有 in_progress 行
        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            for (QuestCatalog.Quest q : chapter.quests()) {
                if (playerQuestRepository.findByPlayerIdAndQuestId(playerId, q.id()).isEmpty()) {
                    ensureRow(playerId, q);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuestCatalog.Chapter chapter : QuestCatalog.CHAPTERS) {
            Map<String, Object> chMap = new LinkedHashMap<>();
            chMap.put("id", chapter.id());
            chMap.put("name", chapter.name());
            chMap.put("intro", chapter.intro());
            List<Map<String, Object>> quests = new ArrayList<>();
            for (QuestCatalog.Quest q : chapter.quests()) {
                PlayerQuest pq = playerQuestRepository.findByPlayerIdAndQuestId(playerId, q.id()).orElse(null);
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("id", q.id());
                qm.put("title", q.title());
                qm.put("desc", q.desc());
                qm.put("target", q.targetValue());
                qm.put("reward", q.reward());
                if (pq != null) {
                    qm.put("status", pq.getStatus());
                    qm.put("progress", pq.getProgress());
                    qm.put("completedAt", pq.getCompletedAt());
                    qm.put("claimedAt", pq.getClaimedAt());
                } else {
                    qm.put("status", "in_progress");
                    qm.put("progress", 0);
                }
                quests.add(qm);
            }
            chMap.put("quests", quests);
            out.add(chMap);
        }
        return out;
    }

    /** 旧接口只返回新版状态，不再发放废弃训练营的奖励。 */
    public Map<String, Object> getGuide(Long playerId) { return onboarding.status(playerId); }

    public Map<String, Object> advanceGuide(Long playerId) { return onboarding.status(playerId); }

    public Map<String, Object> skipGuide(Long playerId) { return onboarding.pause(playerId, true); }
}
