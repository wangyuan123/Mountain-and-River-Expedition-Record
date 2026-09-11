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

    private static final Logger log = LoggerFactory.getLogger(QuestService.class);

    private final PlayerQuestRepository playerQuestRepository;
    private final PlayerGuideRepository playerGuideRepository;
    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final PlayerRepository playerRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final OfficerRepository officerRepository;

    public QuestService(PlayerQuestRepository playerQuestRepository,
                        PlayerGuideRepository playerGuideRepository,
                        ResourcesRepository resourcesRepository,
                        PlayerItemRepository playerItemRepository,
                        PlayerRepository playerRepository,
                        BuildingRepository buildingRepository,
                        ArmyUnitRepository armyUnitRepository,
                        OfficerRepository officerRepository) {
        this.playerQuestRepository = playerQuestRepository;
        this.playerGuideRepository = playerGuideRepository;
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
        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
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

    // ====================================================================
    //  Guide - 新手引导
    // ====================================================================

    /**
     * 拉取新手引导当前状态。
     * <p>
     * 返回结构：
     * <pre>
     *   { id, title, body, nextRoute, order, goal, complete, total, index,
     *     progress: { current, target, pct, complete },
     *     reward }
     * </pre>
     * 或 { done: true } 表示已全部完成。
     */
    @Transactional
    public Map<String, Object> getGuide(Long playerId) {
        QuestCatalog.GuideStep active = findActiveStep(playerId);
        if (active == null) {
            // 全部 done / skipped
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("done", true);
            return end;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", active.id());
        out.put("title", active.title());
        out.put("body", active.body());
        out.put("nextRoute", active.nextRoute());
        out.put("order", active.order());
        out.put("goal", active.goal());
        // UI 跳转目标: 与 checkKey 分离, 前端据此滚动并高亮
        if (active.targetBuilding() != null) {
            out.put("targetBuilding", active.targetBuilding());
        }
        out.put("total", QuestCatalog.NEWBIE_STEPS.size());
        out.put("index", QuestCatalog.NEWBIE_STEPS.indexOf(active) + 1);

        // 实时进度评估
        GuideProgress prog = evaluateGuideStep(playerId, active);
        out.put("progress", prog.toMap());

        // 整段引导是否全部完成
        out.put("complete", prog.complete);

        // 奖励预览
        if (active.reward() != null && !active.reward().isEmpty()) {
            out.put("reward", active.reward());
        }

        return out;
    }

    /**
     * 推进到下一步 - 仅在当前步骤目标达成时允许。
     * <p>
     * 达成时会自动派发该步骤的奖励并标记完成。
     */
    @Transactional
    public Map<String, Object> advanceGuide(Long playerId) {
        QuestCatalog.GuideStep current = findActiveStep(playerId);
        if (current == null) {
            return getGuide(playerId);
        }

        GuideProgress prog = evaluateGuideStep(playerId, current);

        if (!prog.complete) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "目标未完成: " + (current.goal() == null ? current.title() : current.goal())
                    + " (当前 " + prog.current + "/" + prog.target + ")");
            err.put("guide", getGuide(playerId));
            return err;
        }

        // 派发奖励
        if (current.reward() != null && !current.reward().isEmpty()) {
            try {
                grantReward(playerId, current.reward());
            } catch (Exception e) {
                log.warn("Guide reward grant failed: step={} player={} err={}",
                        current.id(), playerId, e.getMessage());
            }
        }

        // 标 done
        PlayerGuide pg = playerGuideRepository.findByPlayerIdAndStepId(playerId, current.id())
                .orElseGet(() -> {
                    PlayerGuide x = new PlayerGuide();
                    x.setPlayerId(playerId);
                    x.setStepId(current.id());
                    return x;
                });
        pg.setStatus("done");
        pg.setCompletedAt(System.currentTimeMillis());
        playerGuideRepository.save(pg);

        log.info("Guide step done: player={} step={} rewardGiven={}",
                playerId, current.id(), current.reward() != null && !current.reward().isEmpty());

        // 返回下一步骤的同时带回本次实际入账奖励，供前端明确提示玩家。
        Map<String, Object> nextGuide = getGuide(playerId);
        if (current.reward() != null && !current.reward().isEmpty()) {
            nextGuide.put("completedReward", current.reward());
        }
        nextGuide.put("completedStepTitle", current.title());
        return nextGuide;
    }

    @Transactional
    public Map<String, Object> skipGuide(Long playerId) {
        for (QuestCatalog.GuideStep s : QuestCatalog.NEWBIE_STEPS) {
            PlayerGuide pg = playerGuideRepository.findByPlayerIdAndStepId(playerId, s.id()).orElse(null);
            if (pg != null && "active".equals(pg.getStatus())) {
                pg.setStatus("skipped");
                pg.setCompletedAt(System.currentTimeMillis());
                playerGuideRepository.save(pg);
            }
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("done", true);
        return end;
    }

    // ----------------------------------------------------------------
    //  内部：找当前 active 步骤
    // ----------------------------------------------------------------

    /**
     * 找当前 active 步骤。
     * <p>
     * 规则（按 NEWBIE_STEPS 顺序）：
     * <ol>
     *   <li>第一个无 DB 行的步骤 = 当前 active (玩家刚走到这里)</li>
     *   <li>若所有步骤都有行且都是 done/skipped, 则 active = null (引导完成)</li>
     *   <li>若遇到 status="active" 的行, 视为"在执行中", 但:
     *      <ul>
     *          <li>若更靠前的步骤无行 → 把它当脏数据 (旧的 active 行遗留), 修正为 done, 继续找真正的 active</li>
     *          <li>否则它就是当前 active</li>
     *      </ul>
     *   </li>
     * </ol>
     */
    private QuestCatalog.GuideStep findActiveStep(Long playerId) {
        // 一次性拉全部行, 避免 N 次查询
        Map<String, PlayerGuide> pgMap = new HashMap<>();
        for (PlayerGuide pg : playerGuideRepository.findByPlayerId(playerId)) {
            pgMap.put(pg.getStepId(), pg);
        }

        QuestCatalog.GuideStep firstNoRow = null;
        QuestCatalog.GuideStep activeRowStep = null;
        int firstNoRowIdx = -1;
        int activeRowIdx = -1;

        for (int i = 0; i < QuestCatalog.NEWBIE_STEPS.size(); i++) {
            QuestCatalog.GuideStep s = QuestCatalog.NEWBIE_STEPS.get(i);
            PlayerGuide pg = pgMap.get(s.id());
            if (pg == null) {
                if (firstNoRowIdx < 0) {
                    firstNoRowIdx = i;
                    firstNoRow = s;
                }
            } else if ("active".equals(pg.getStatus())) {
                if (activeRowIdx < 0) {
                    activeRowIdx = i;
                    activeRowStep = s;
                }
            }
        }

        // 情况 1: 有"无行"步骤, 且它比任何"active"行更靠前
        //         → 那个"active"行是脏数据 (旧系统遗留), 修正它
        if (firstNoRowIdx >= 0 && (activeRowIdx < 0 || firstNoRowIdx < activeRowIdx)) {
            if (activeRowIdx >= 0) {
                // 修正脏数据: 把"active"行标 done
                PlayerGuide stale = pgMap.get(activeRowStep.id());
                if (stale != null) {
                    stale.setStatus("done");
                    stale.setCompletedAt(System.currentTimeMillis());
                    playerGuideRepository.save(stale);
                    log.info("Guide: cleaning stale active row player={} step={}",
                            playerId, activeRowStep.id());
                }
            }
            return firstNoRow;
        }

        // 情况 2: 没有任何"无行"步骤
        if (firstNoRowIdx < 0) {
            // 有 "active" 行 → 它是当前 active
            // 否则 → 全部 done, 引导完成 (返回 null)
            return activeRowStep;
        }

        // 情况 3: 只有"无行"步骤, 没有 active 行
        return firstNoRow;
    }

    // ----------------------------------------------------------------
    //  内部：评估一个引导步骤的实时进度
    // ----------------------------------------------------------------

    /** 步骤进度快照。 */
    public record GuideProgress(int current, int target, boolean complete) {
        public int pct() {
            if (target <= 0) return complete ? 100 : 0;
            return Math.min(100, (int) Math.round(current * 100.0 / target));
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("current", current);
            m.put("target", target);
            m.put("pct", pct());
            m.put("complete", complete);
            return m;
        }
    }

    public GuideProgress evaluateGuideStep(Long playerId, QuestCatalog.GuideStep step) {
        String type = step.checkType() == null ? "NONE" : step.checkType();
        if ("NONE".equals(type)) {
            return new GuideProgress(1, 1, true);
        }
        int current = 0;
        int target = Math.max(1, step.checkValue());
        switch (type) {
            case "BUILD_LEVEL": {
                // 取最高槽位等级 (不计算"已计划但未完成"的施工, 避免点 + 立刻达成)
                // 玩家必须真的等建造倒计时跑完, 升级完才视为达成
                List<Building> bs = buildingRepository.findByPlayerIdAndType(playerId, step.checkKey());
                for (Building b : bs) {
                    int lv = b.getLevel() != null ? b.getLevel() : 0;
                    if (lv > current) current = lv;
                }
                break;
            }
            case "BUILD_LEVEL_SUM": {
                List<Building> bs = buildingRepository.findByPlayerIdAndType(playerId, step.checkKey());
                for (Building b : bs) {
                    current += b.getLevel() != null ? b.getLevel() : 0;
                }
                break;
            }
            case "BUILD_COUNT": {
                current = buildingRepository.findByPlayerIdAndType(playerId, step.checkKey()).size();
                break;
            }
            case "ARMY_RECRUIT": {
                List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndType(playerId, step.checkKey());
                for (ArmyUnit u : units) {
                    current += u.getCount() != null ? u.getCount() : 0;
                }
                break;
            }
            case "ARMY_TOTAL": {
                List<ArmyUnit> all = armyUnitRepository.findByPlayerId(playerId);
                for (ArmyUnit u : all) {
                    current += u.getCount() != null ? u.getCount() : 0;
                }
                break;
            }
            case "OFFICER_RECRUIT": {
                current = officerRepository.findByPlayerId(playerId).size();
                break;
            }
            case "OFFICER_APPOINT": {
                String role = step.checkKey() == null ? "any" : step.checkKey();
                if ("any".equalsIgnoreCase(role)) {
                    List<Officer> os = officerRepository.findByPlayerId(playerId);
                    for (Officer o : os) {
                        String r = o.getRole() == null ? "idle" : o.getRole();
                        if (!"idle".equals(r)) { current = 1; break; }
                    }
                } else {
                    current = officerRepository.findByPlayerIdAndRole(playerId, role).size();
                }
                break;
            }
            default:
                // 未知类型: 视为达成
                return new GuideProgress(1, 1, true);
        }
        boolean complete = current >= target;
        return new GuideProgress(current, target, complete);
    }
}
