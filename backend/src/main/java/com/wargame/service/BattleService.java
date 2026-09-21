package com.wargame.service;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.BattleResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务端战斗结算：针对目标领域的线性攻击 × 专项克制 × 100/(100 + 5×有效防御)。
 * 每次行动共享一份攻击额度；跨地空海及工事切换目标时按剩余额度使用对应武器，不能重新打一轮。
 * 重坦前置且掩护身后的地面单位，特种兵可绕过掩护；空海目标不受地面掩护影响。
 */
@Service
public class BattleService {

    public static final int MAX_ROUND = 30;
    private final java.util.function.DoubleSupplier random;

    public BattleService() {
        random = () -> ThreadLocalRandom.current().nextDouble();
    }

    /** 固定种子用于复现平衡实验；默认服务仍按每次战斗的随机抽样结算。 */
    public BattleService(long seed) {
        random = new Random(seed)::nextDouble;
    }

    /** 军官技能每级加成比率 - 对应 JS Core.skillBonus 中的 rates */
    private static final Map<String, Double> SKILL_RATES = Map.ofEntries(
            Map.entry("frenzy", 0.10),
            Map.entry("bulwark", 0.10),
            Map.entry("blitz", 0.06),
            Map.entry("suppress", 0.06),
            Map.entry("pierce", 0.06),
            Map.entry("leadership", 0.04),
            Map.entry("supply", 0.04),
            Map.entry("medic", 0.03),
            Map.entry("counter", 0.10),
            Map.entry("ration", 0.16)
    );

    /** 战术机动指令类型 */
    public enum CommandAction {
        ADVANCE, // 前进（接敌/压迫）
        RETREAT, // 后退（拉扯/边打边退）
        HOLD     // 待命（坚守阵地/不移动）
    }

    /** 单位战术指令：机动行为 + 指定集火目标 (可选) */
    public record UnitOrder(CommandAction action, String focusTarget) {
        public UnitOrder(CommandAction action) {
            this(action, null);
        }
    }

    // ========================================================================
    // 内部数据结构
    // ========================================================================

    /** 统一单位/城防属性 - 对应 JS U(id) 返回值的常用字段 */
    private record UnitStats(String key, String name, String cat,
                             double atkGround, double atkAir, double atkSea, double atkFort, double def, double hp, int spd, int range,
                             String strongVs, boolean autoAdvance) {
        double peakTroopAttack() { return Math.max(atkGround, Math.max(atkAir, atkSea)); }
    }

    /** 从 UnitDef 或 FortDef 获取统一属性 - 对应 JS U(id) */
    private UnitStats getStats(String id) {
        if (id == null) return null;
        UnitDef u = GameData.UNITS.get(id);
        if (u != null) {
            return new UnitStats(u.key(), u.name(), u.cat(),
                    u.atkGround(), u.atkAir(), u.atkSea(), u.atkFort(), u.def(), u.hp(), u.spd(), u.range(),
                    u.strongVs(), u.autoAdvance() == null || u.autoAdvance());
        }
        FortDef f = GameData.FORTS.get(id);
        if (f != null) {
            return new UnitStats(f.key(), f.name(), f.cat(),
                    f.atkGround(), f.atkAir(), f.atkSea(), f.atkFort(), f.def(), f.hp(), f.spd(), f.range(),
                    f.strongVs(), f.autoAdvance());
        }
        return null;
    }

    /**
     * 默认机动指令解析：根据单位的 autoAdvance 属性决定。
     */
    private CommandAction defaultAction(String unitId) {
        UnitStats u = getStats(unitId);
        if (u == null || !u.autoAdvance()) {
            return CommandAction.HOLD;
        }
        return CommandAction.ADVANCE;
    }

    /**
     * 动态战场初始距离计算公式：
     * D = max(1000, 3 * (maxSpdA + maxSpdB) * 50 + maxRange)
     */
    public int calcInitialDistance(Map<String, Integer> attackerArmy,
                                  Map<String, Integer> defenderArmy,
                                  TechCtx attackerCtx,
                                  TechCtx defenderCtx) {
        double maxSpdA = 0;
        int maxRangeA = 0;
        if (attackerArmy != null) {
            for (Map.Entry<String, Integer> e : attackerArmy.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    double spd = attackerCtx != null ? effSpd(e.getKey(), attackerCtx.tech, attackerCtx.skills) : (getStats(e.getKey()) != null ? getStats(e.getKey()).spd() : 0);
                    int r = effectiveRange(e.getKey(), attackerCtx);
                    if (spd > maxSpdA) maxSpdA = spd;
                    if (r > maxRangeA) maxRangeA = r;
                }
            }
        }

        double maxSpdB = 0;
        int maxRangeB = 0;
        if (defenderArmy != null) {
            for (Map.Entry<String, Integer> e : defenderArmy.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    double spd = defenderCtx != null ? effSpd(e.getKey(), defenderCtx.tech, defenderCtx.skills) : (getStats(e.getKey()) != null ? getStats(e.getKey()).spd() : 0);
                    int r = effectiveRange(e.getKey(), defenderCtx);
                    if (spd > maxSpdB) maxSpdB = spd;
                    if (r > maxRangeB) maxRangeB = r;
                }
            }
        }

        int maxRange = Math.max(maxRangeA, maxRangeB);
        int calcDist = (int) Math.round(3 * (maxSpdA + maxSpdB) * 50 + maxRange);
        return Math.max(1000, calcDist);
    }

    // ========================================================================
    // resolveWild - 野地战斗 (无科技/军官加成, 使用基础属性)
    // ========================================================================

    /**
     * 解析野地战斗 (默认指令) - 对应 JS G.Battle.resolveWild(garrison, mine)。
     */
    public BattleResult resolveWild(Map<String, Integer> garrison, Map<String, Integer> attacker) {
        return resolveWild(garrison, attacker, null, null);
    }

    /**
     * 解析野地战斗 (支持玩家与防守方自定义战术指令)。
     */
    public BattleResult resolveWild(Map<String, Integer> garrison, Map<String, Integer> attacker,
                                    Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders) {
        Map<String, Integer> myArmy = snapshot(attacker);
        Map<String, Integer> foeArmy = snapshot(garrison);
        Map<String, Integer> myStart = snapshot(myArmy);
        Map<String, Integer> foeStart = snapshot(foeArmy);

        int initialDist = calcInitialDistance(myArmy, foeArmy, null, null);
        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : myArmy.keySet()) minePos.put(k, formationOffset(k));
        for (String k : foeArmy.keySet()) enemyPos.put(k, initialDist - formationOffset(k));

        StringBuilder report = new StringBuilder("规则版本：" + BattleRules.VERSION + "\n");
        report.append("战场初始距离: ").append(initialDist).append("\n");

        for (int round = 1; round <= MAX_ROUND; round++) {
            report.append("-- 第").append(round).append("回合 --\n");

            // 阶段一: 统一机动
            executeMovementPhase(myArmy, foeArmy, minePos, enemyPos, initialDist, report,
                    attackerOrders, defenderOrders, null, null);

            // 阶段二: 战术交火
            executeCombatPhase(myArmy, foeArmy, minePos, enemyPos, initialDist, report,
                    attackerOrders, defenderOrders, null, null, 0, 0, false, round);

            if (allDead(foeArmy)) {
                return buildWildResult(true, myArmy, foeArmy, myStart, foeStart, report);
            }
            if (allDead(myArmy)) {
                return buildWildResult(false, myArmy, foeArmy, myStart, foeStart, report);
            }
        }
        boolean win = allDead(foeArmy);
        return buildWildResult(win, myArmy, foeArmy, myStart, foeStart, report);
    }

    // ========================================================================
    // startWorldDispatch - 世界出征战斗 (含科技/军官/城防加成)
    // ========================================================================

    /**
     * 解析世界出征战斗 - 对应 JS G.Battle.startWorldDispatch + resolveRound + finish。
     */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy,
            Map<String, Integer> defenderArmy,
            Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech,
            Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills,
            Map<String, Integer> defenderSkills,
            int attackerCommanderMil,
            int defenderCommanderMil,
            int attackerWallLevel,
            int defenderWallLevel,
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, 0,
                defenderCommanderMil, 0,
                attackerWallLevel, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel, false,
                null, null);
    }

    /**
     * 解析世界出征战斗 (支持区分 NPC 战斗与玩家对战)。
     */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy,
            Map<String, Integer> defenderArmy,
            Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech,
            Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills,
            Map<String, Integer> defenderSkills,
            int attackerCommanderMil,
            int defenderCommanderMil,
            int attackerWallLevel,
            int defenderWallLevel,
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, 0,
                defenderCommanderMil, 0,
                attackerWallLevel, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel, isPlayerBattle,
                null, null);
    }

    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy,
            Map<String, Integer> defenderArmy,
            Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech,
            Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills,
            Map<String, Integer> defenderSkills,
            int attackerCommanderMil,
            int attackerCommanderDef,
            int defenderCommanderMil,
            int defenderCommanderDef,
            int attackerWallLevel,
            int defenderWallLevel,
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef,
                attackerWallLevel, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel, isPlayerBattle,
                null, null);
    }

    /**
     * 解析世界出征战斗 (完整参数：支持自定义战术指令与攻防将领四维属性)。
     */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy,
            Map<String, Integer> defenderArmy,
            Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech,
            Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills,
            Map<String, Integer> defenderSkills,
            int attackerCommanderMil,
            int defenderCommanderMil,
            int attackerWallLevel,
            int defenderWallLevel,
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, 0,
                defenderCommanderMil, 0,
                attackerWallLevel, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel, isPlayerBattle,
                attackerOrders, defenderOrders);
    }

    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy,
            Map<String, Integer> defenderArmy,
            Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech,
            Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills,
            Map<String, Integer> defenderSkills,
            int attackerCommanderMil,
            int attackerCommanderDef,
            int defenderCommanderMil,
            int defenderCommanderDef,
            int attackerWallLevel,
            int defenderWallLevel,
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders) {

        // 克隆军队 (不修改原始 map)
        Map<String, Integer> mine = snapshot(attackerArmy);
        Map<String, Integer> enemy = snapshot(defenderArmy);

        // 将城防加入守方军队 - 对应 JS startWorldDispatch 中 target.forts 的处理
        if (defenderForts != null) {
            for (Map.Entry<String, Integer> e : defenderForts.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    enemy.put(e.getKey(), e.getValue());
                }
            }
        }

        // 空安全
        Map<String, Integer> aTech = attackerTech != null ? attackerTech : Collections.emptyMap();
        Map<String, Integer> dTech = defenderTech != null ? defenderTech : Collections.emptyMap();
        Map<String, Integer> aSkills = attackerSkills != null ? attackerSkills : Collections.emptyMap();
        Map<String, Integer> dSkills = defenderSkills != null ? defenderSkills : Collections.emptyMap();

        TechCtx attackerCtx = new TechCtx(aTech, aSkills, attackerCommanderMil, attackerCommanderDef);
        TechCtx defenderCtx = new TechCtx(dTech, dSkills, defenderCommanderMil, defenderCommanderDef);

        // 保存初始军队快照 (用于战后经验计算) - 对应 JS mineStart / enemyStart
        Map<String, Integer> mineStart = snapshot(mine);
        Map<String, Integer> enemyStart = snapshot(enemy);

        // 动态计算初始战场距离
        int initialDist = calcInitialDistance(mine, enemy, attackerCtx, defenderCtx);

        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : mine.keySet()) minePos.put(k, formationOffset(k));
        for (String k : enemy.keySet()) enemyPos.put(k, initialDist - formationOffset(k));

        StringBuilder report = new StringBuilder("规则版本：" + BattleRules.VERSION + "\n");
        report.append("战场初始距离: ").append(initialDist).append("\n");

        // 模拟 30 回合 - 对应 JS resolveRound 循环
        for (int round = 1; round <= MAX_ROUND; round++) {
            boolean officerActive = (round % 3 == 0);

            report.append("-- 第").append(round).append("回合 (军官加成生效) --\n");

            String mineBonusLog = buildCommanderBonusLog("我方", attackerCtx, round, true);
            String foeBonusLog = buildCommanderBonusLog("敌方", defenderCtx, round, false);
            if (!mineBonusLog.isEmpty()) report.append(mineBonusLog).append("\n");
            if (!foeBonusLog.isEmpty()) report.append(foeBonusLog).append("\n");

            // 阶段一: 统一机动阶段
            executeMovementPhase(mine, enemy, minePos, enemyPos, initialDist, report,
                    attackerOrders, defenderOrders, attackerCtx, defenderCtx);

            // 阶段二: 战术交火阶段
            executeCombatPhase(mine, enemy, minePos, enemyPos, initialDist, report,
                    attackerOrders, defenderOrders, attackerCtx, defenderCtx,
                    attackerWallLevel, defenderWallLevel, officerActive, round);

            // 胜负判定
            if (allDead(enemy)) {
                report.append("★ 全歼敌军,胜利!\n");
                return finishBattle(true, mine, enemy, mineStart, enemyStart,
                        aTech, action, defenderResources, defenderWarehouseLevel, report);
            }
            if (allDead(mine)) {
                report.append("✗ 部队溃败,失败!\n");
                return finishBattle(false, mine, enemy, mineStart, enemyStart,
                        aTech, action, defenderResources, defenderWarehouseLevel, report);
            }
            if (round >= MAX_ROUND) {
                report.append("✗ 回合耗尽,被迫撤退\n");
                return finishBattle(false, mine, enemy, mineStart, enemyStart,
                        aTech, action, defenderResources, defenderWarehouseLevel, report);
            }
        }

        // 安全兜底
        return finishBattle(false, mine, enemy, mineStart, enemyStart,
                aTech, action, defenderResources, defenderWarehouseLevel, report);
    }

    /**
     * 结算战术战斗的一回合。调用方负责持久化返回的位置和存活单位，确保刷新页面不会重置战场。
     */
    public BattleRoundState resolveWorldRound(Map<String, Integer> attackerArmy,
                                              Map<String, Integer> defenderArmy,
                                              Map<String, Integer> attackerPositions,
                                              Map<String, Integer> defenderPositions,
                                              int initialDistance,
                                              Map<String, Integer> attackerTech,
                                              Map<String, Integer> defenderTech,
                                              Map<String, Integer> attackerSkills,
                                              Map<String, Integer> defenderSkills,
                                              int attackerCommanderMil,
                                              int attackerCommanderDef,
                                              int defenderCommanderMil,
                                              int defenderCommanderDef,
                                              int attackerWallLevel,
                                              int defenderWallLevel,
                                              int round,
                                              Map<String, UnitOrder> attackerOrders,
                                              Map<String, UnitOrder> defenderOrders) {
        Map<String, Integer> mine = snapshot(attackerArmy);
        Map<String, Integer> enemy = snapshot(defenderArmy);
        Map<String, Integer> minePos = new LinkedHashMap<>(attackerPositions);
        Map<String, Integer> enemyPos = new LinkedHashMap<>(defenderPositions);
        TechCtx attackerCtx = new TechCtx(attackerTech != null ? attackerTech : Collections.emptyMap(),
                attackerSkills != null ? attackerSkills : Collections.emptyMap(), attackerCommanderMil, attackerCommanderDef);
        TechCtx defenderCtx = new TechCtx(defenderTech != null ? defenderTech : Collections.emptyMap(),
                defenderSkills != null ? defenderSkills : Collections.emptyMap(), defenderCommanderMil, defenderCommanderDef);
        boolean officerActive = round % 3 == 0;
        StringBuilder report = new StringBuilder("-- 第").append(round).append("回合 (军官加成生效) --\n");

        String mineBonusLog = buildCommanderBonusLog("我方", attackerCtx, round, true);
        String foeBonusLog = buildCommanderBonusLog("敌方", defenderCtx, round, false);
        if (!mineBonusLog.isEmpty()) report.append(mineBonusLog).append("\n");
        if (!foeBonusLog.isEmpty()) report.append(foeBonusLog).append("\n");

        executeMovementPhase(mine, enemy, minePos, enemyPos, initialDistance, report,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx);
        executeCombatPhase(mine, enemy, minePos, enemyPos, initialDistance, report,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx,
                attackerWallLevel, defenderWallLevel, officerActive, round);

        boolean attackerWin = allDead(enemy);
        boolean defenderWin = allDead(mine) || (!attackerWin && round >= MAX_ROUND);
        if (attackerWin) report.append("★ 全歼敌军,胜利!\n");
        else if (defenderWin && allDead(mine)) report.append("✗ 部队溃败,失败!\n");
        else if (defenderWin) report.append("✗ 回合耗尽,被迫撤退\n");

        return new BattleRoundState(round, attackerWin || defenderWin, attackerWin,
                filterPositive(mine), filterPositive(enemy), minePos, enemyPos, report.toString());
    }

    /**
     * 基于已完成的逐回合战场状态生成经验、掠夺和战报等最终结算数据。
     */
    public BattleResult finishWorldBattle(boolean win,
                                          Map<String, Integer> survivorAttacker,
                                          Map<String, Integer> survivorDefender,
                                          Map<String, Integer> initialAttacker,
                                          Map<String, Integer> initialDefender,
                                          Map<String, Integer> attackerTech,
                                          String action,
                                          Map<String, Integer> defenderResources,
                                          long defenderWarehouseLevel,
                                          String report) {
        return finishBattle(win, survivorAttacker, survivorDefender, initialAttacker, initialDefender,
                attackerTech != null ? attackerTech : Collections.emptyMap(), action, defenderResources,
                defenderWarehouseLevel, new StringBuilder(report != null ? report : ""));
    }

    // ========================================================================
    // finishBattle - 战斗结算 (对应 JS Battle.finish)
    // ========================================================================

    private BattleResult finishBattle(boolean win,
                                      Map<String, Integer> mine,
                                      Map<String, Integer> enemy,
                                      Map<String, Integer> mineStart,
                                      Map<String, Integer> enemyStart,
                                      Map<String, Integer> attackerTech,
                                      String action,
                                      Map<String, Integer> defenderResources,
                                      long defenderWarehouseLevel,
                                      StringBuilder report) {
        // 计算击杀经验 - 对应 JS calcKillExp
        // enemyKilled[eid] = enemyStart[eid] - enemy[eid]
        Map<String, Integer> enemyKilled = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : enemyStart.entrySet()) {
            int start = e.getValue() != null ? e.getValue() : 0;
            int now = enemy.getOrDefault(e.getKey(), 0);
            int killed = start - now;
            if (killed > 0) enemyKilled.put(e.getKey(), killed);
        }
        int expGained = calcKillExp(enemyKilled);

        // 战场幸存者直接返还；实际损失的可救治部分另由伤兵营记录，不自动复活。
        Map<String, Integer> survivorAttacker = filterPositive(mine);

        // 幸存守方单位
        Map<String, Integer> survivorDefender = filterPositive(enemy);

        // 掠夺资源 - 对应 JS finish 中 reward 计算
        Map<String, Integer> plunderedResources = new LinkedHashMap<>();
        boolean cityConquered = false;
        if (win) {
            String act = action != null ? action : "conquer";
            if (defenderResources != null && !defenderResources.isEmpty()) {
                if ("conquer".equals(act)) {
                    // 征服: 夺取全部资源 (含黄金) - 对应 JS conquer 分支
                    for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                        plunderedResources.put(rk, defenderResources.getOrDefault(rk, 0));
                    }
                    cityConquered = true;
                    report.append("★ 征服胜利! 夺取敌方全部资源。\n");
                } else if ("plunder".equals(act)) {
                    // 掠夺: 仓库保护外的非黄金资源 - 对应 JS plunder 分支
                    int prot = calcProtectCap(defenderWarehouseLevel);
                    for (String rk : List.of("food", "steel", "oil", "rare")) {
                        int amount = defenderResources.getOrDefault(rk, 0);
                        plunderedResources.put(rk, Math.max(0, amount - prot));
                    }
                    plunderedResources.put("gold", 0);
                    report.append("★ 掠夺成功! 仓库保护").append(prot).append("。\n");
                    expGained = expGained / 2; // 掠夺经验减半 - 对应 JS exp * 0.5
                } else {
                    for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                        plunderedResources.put(rk, 0);
                    }
                }
            } else {
                for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                    plunderedResources.put(rk, 0);
                }
            }
        } else {
            for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                plunderedResources.put(rk, 0);
            }
        }

        // 军官经验 - 对应 JS finish 中 _officerExpGain (败战仅 30%)
        if (!win) {
            expGained = (int) Math.floor(expGained * 0.3);
        }

        report.append("获得经验: ").append(expGained).append("\n");

        Map<String, Integer> initialAttacker = filterPositive(mineStart);
        Map<String, Integer> initialDefender = filterPositive(enemyStart);
        return new BattleResult(win, survivorAttacker, survivorDefender,
                initialAttacker, initialDefender,
                plunderedResources, expGained, report.toString(), cityConquered);
    }

    // ========================================================================
    // simAct - 单个单位行动 (对应 JS Battle.simAct)
    // ========================================================================

    // ========================================================================
    // 阶段一: 统一机动阶段 (Movement Phase)
    // 双方所有存活单位同时结算前进/后退/待命，刷新战场坐标，阵地边界不可穿透
    // ========================================================================

    private void executeMovementPhase(
            Map<String, Integer> mine,
            Map<String, Integer> enemy,
            Map<String, Integer> minePos,
            Map<String, Integer> enemyPos,
            int initialDist,
            StringBuilder report,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            TechCtx attackerCtx,
            TechCtx defenderCtx) {

        // 快照移动前坐标，避免先后计算造成不对称
        Map<String, Integer> oldMinePos = new LinkedHashMap<>(minePos);
        Map<String, Integer> oldEnemyPos = new LinkedHashMap<>(enemyPos);

        // 1. 攻方机动
        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            String unitId = e.getKey();
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count <= 0) continue;
            UnitStats u = getStats(unitId);
            if (u == null) continue;

            UnitOrder order = attackerOrders != null ? attackerOrders.get(unitId) : null;
            CommandAction action = order != null ? order.action() : defaultAction(unitId);

            double spd = attackerCtx != null ? effSpd(unitId, attackerCtx.tech, attackerCtx.skills) : u.spd();
            int rawStep = (int) (spd * 50);
            int curX = minePos.getOrDefault(unitId, formationOffset(unitId));

            if (action == CommandAction.ADVANCE) {
                if (!u.autoAdvance() && order == null) {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [待命] 原地待命 坐标").append(curX).append("\n");
                    continue;
                }
                int minDist = minDistanceToLivingFoe(Side.MINE, unitId, enemy, oldMinePos, oldEnemyPos, initialDist);
                if (minDist == Integer.MAX_VALUE) {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [前进] 无可攻击目标 原地待命 坐标").append(curX).append("\n");
                    continue;
                }
                int range = effectiveRange(unitId, attackerCtx);
                int neededDist = Math.max(0, minDist - range);
                int step = Math.min(rawStep, neededDist);
                step = Math.min(step, Math.max(0, initialDist - curX));
                int newX = curX + step;
                minePos.put(unitId, newX);
                if (step > 0) {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [前进] 推进").append(step).append(" -> 坐标").append(newX)
                            .append(" (距敌").append(minDist - step).append(")\n");
                } else {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [前进] 已进入射程(距敌").append(minDist).append(") 原地保持 坐标").append(newX).append("\n");
                }
            } else if (action == CommandAction.RETREAT) {
                int step = Math.min(rawStep, curX);
                int newX = curX - step;
                minePos.put(unitId, newX);
                if (curX <= 0) {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [后退] 已达阵地底线(坐标0) 退无可退\n");
                } else {
                    report.append("我方").append(u.name()).append("(").append(count)
                            .append(") [后退] 撤退").append(step).append(" -> 坐标").append(newX).append("\n");
                }
            } else {
                report.append("我方").append(u.name()).append("(").append(count)
                        .append(") [待命] 坚守阵地 坐标").append(curX).append("\n");
            }
        }

        // 2. 守方机动
        for (Map.Entry<String, Integer> e : enemy.entrySet()) {
            String unitId = e.getKey();
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count <= 0) continue;
            UnitStats u = getStats(unitId);
            if (u == null) continue;

            UnitOrder order = defenderOrders != null ? defenderOrders.get(unitId) : null;
            CommandAction action = order != null ? order.action() : defaultAction(unitId);

            double spd = defenderCtx != null ? effSpd(unitId, defenderCtx.tech, defenderCtx.skills) : u.spd();
            int rawStep = (int) (spd * 50);
            int curX = enemyPos.getOrDefault(unitId, initialDist - formationOffset(unitId));

            if (action == CommandAction.ADVANCE) {
                if (!u.autoAdvance() && order == null) {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [待命] 原地待命 坐标").append(curX).append("\n");
                    continue;
                }
                int minDist = minDistanceToLivingFoe(Side.ENEMY, unitId, mine, oldMinePos, oldEnemyPos, initialDist);
                if (minDist == Integer.MAX_VALUE) {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [前进] 无可攻击目标 原地待命 坐标").append(curX).append("\n");
                    continue;
                }
                int range = effectiveRange(unitId, defenderCtx);
                int neededDist = Math.max(0, minDist - range);
                int step = Math.min(rawStep, neededDist);
                step = Math.min(step, curX);
                int newX = curX - step;
                enemyPos.put(unitId, newX);
                if (step > 0) {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [前进] 推进").append(step).append(" -> 坐标").append(newX)
                            .append(" (距我").append(minDist - step).append(")\n");
                } else {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [前进] 已进入射程(距我").append(minDist).append(") 原地保持 坐标").append(newX).append("\n");
                }
            } else if (action == CommandAction.RETREAT) {
                int step = Math.min(rawStep, Math.max(0, initialDist - curX));
                int newX = curX + step;
                enemyPos.put(unitId, newX);
                if (curX >= initialDist) {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [后退] 已达阵地底线(坐标").append(initialDist).append(") 退无可退\n");
                } else {
                    report.append("敌方").append(u.name()).append("(").append(count)
                            .append(") [后退] 撤退").append(step).append(" -> 坐标").append(newX).append("\n");
                }
            } else {
                report.append("敌方").append(u.name()).append("(").append(count)
                        .append(") [待命] 坚守阵地 坐标").append(curX).append("\n");
            }
        }
    }

    // ========================================================================
    // 阶段二: 战术交火阶段 (Combat Phase)
    // 按射程降序、速度降序依次开火；优先集火目标，支持边打边退
    // ========================================================================

    private void executeCombatPhase(
            Map<String, Integer> mine,
            Map<String, Integer> enemy,
            Map<String, Integer> minePos,
            Map<String, Integer> enemyPos,
            int initialDist,
            StringBuilder report,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            TechCtx attackerCtx,
            TechCtx defenderCtx,
            int attackerWallLevel,
            int defenderWallLevel,
            boolean officerActive,
            int round) {

        boolean frenzyActive = round % 3 == 1;
        boolean bulwarkActive = round % 3 == 2;
        List<ActionEntry> order = buildOrder(mine, enemy, attackerCtx, defenderCtx, round);
        for (ActionEntry a : order) {
            Map<String, Integer> myA = a.side == Side.MINE ? mine : enemy;
            Map<String, Integer> foe = a.side == Side.MINE ? enemy : mine;
            if (myA.getOrDefault(a.id, 0) <= 0) continue;
            if (allDead(foe)) break;

            TechCtx actCtx = a.side == Side.MINE ? attackerCtx : defenderCtx;
            TechCtx foeCtx = a.side == Side.MINE ? defenderCtx : attackerCtx;
            int foeWallLevel = a.side == Side.MINE ? defenderWallLevel : attackerWallLevel;
            int myWallLevel = a.side == Side.MINE ? attackerWallLevel : defenderWallLevel;

            UnitOrder uOrder = a.side == Side.MINE
                    ? (attackerOrders != null ? attackerOrders.get(a.id) : null)
                    : (defenderOrders != null ? defenderOrders.get(a.id) : null);
            String focusTarget = uOrder != null ? uOrder.focusTarget() : null;

            fireUnitCombat(a.id, myA, foe, minePos, enemyPos, initialDist, report, a.side,
                    actCtx, foeCtx, foeWallLevel, myWallLevel, officerActive,
                    frenzyActive, bulwarkActive, focusTarget, false);
        }
    }

    /**
     * 选择交火目标：优先指定集火目标（受重坦掩护与特种兵穿透约束），否则按常规克制权重选取。
     */
    private String pickCombatTarget(String attackerId, Side side, Map<String, Integer> foeArmy,
                                    Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                    int initialDist, TechCtx ctx, String focusTarget) {
        if (focusTarget != null && foeArmy.getOrDefault(focusTarget, 0) > 0 && baseAttack(attackerId, focusTarget) > 0) {
            int range = effectiveRange(attackerId, ctx);
            int dist = getUnitDistToFoe(side, attackerId, focusTarget, minePos, enemyPos, initialDist);
            if (dist <= range) {
                int tankDistance = foeArmy.getOrDefault("htank", 0) > 0
                        ? getUnitDistToFoe(side, attackerId, "htank", minePos, enemyPos, initialDist)
                        : Integer.MAX_VALUE;
                boolean blockedByTank = !"special".equals(attackerId) && !"htank".equals(focusTarget)
                        && BattleRules.ground(focusTarget)
                        && tankDistance <= dist && tankDistance <= range;
                if (!blockedByTank) {
                    return focusTarget;
                }
            }
        }
        return pickTargetInRange(attackerId, side, foeArmy, minePos, enemyPos, initialDist, ctx);
    }

    private void fireUnitCombat(String unitId,
                                Map<String, Integer> myArmy,
                                Map<String, Integer> foeArmy,
                                Map<String, Integer> minePos,
                                Map<String, Integer> enemyPos,
                                int initialDist,
                                StringBuilder report,
                                Side side,
                                TechCtx actCtx,
                                TechCtx foeCtx,
                                int foeWallLevel,
                                boolean officerActive,
                                String focusTarget) {
        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side,
                actCtx, foeCtx, foeWallLevel, 0, officerActive,
                officerActive, officerActive, focusTarget, false);
    }

    /**
     * 执行单个单位的交火逻辑。
     */
    private void fireUnitCombat(String unitId,
                                Map<String, Integer> myArmy,
                                Map<String, Integer> foeArmy,
                                Map<String, Integer> minePos,
                                Map<String, Integer> enemyPos,
                                int initialDist,
                                StringBuilder report,
                                Side side,
                                TechCtx actCtx,
                                TechCtx foeCtx,
                                int foeWallLevel,
                                int myWallLevel,
                                boolean officerActive,
                                boolean frenzyActive,
                                boolean bulwarkActive,
                                String focusTarget,
                                boolean isCounter) {
        UnitStats u = getStats(unitId);
        if (u == null) return;
        int count = myArmy.getOrDefault(unitId, 0);
        if (count <= 0) return;

        String sidePrefix = side == Side.MINE ? "我方" : "敌方";

        // 寻找射程内目标
        String target = pickCombatTarget(unitId, side, foeArmy, minePos, enemyPos, initialDist, actCtx, focusTarget);
        if (target == null) {
            int minDist = minDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
            if (minDist == Integer.MAX_VALUE) {
                report.append(sidePrefix).append(u.name()).append("(").append(count).append(") 射程外待机 无可攻击目标\n");
            } else {
                report.append(sidePrefix).append(u.name()).append("(").append(count).append(") 射程外待机 (最近敌军距离").append(minDist).append(")\n");
            }
            return;
        }

        // 射程内开火
        double attackBonus = actCtx == null ? 1
                : attackBonus(u.cat(), actCtx.tech, actCtx.skills, actCtx.commanderMil,
                        officerActive, frenzyActive);
        if (foeCtx != null) attackBonus *= 1 - skillBonus(foeCtx.skills, "suppress");
        double pierce = actCtx == null ? 0 : skillBonus(actCtx.skills, "pierce");
        double totalActions = count;
        double remainingActions = totalActions;
        boolean firstTarget = true;

        while (target != null && remainingActions > 1e-9) {
            UnitStats tU = getStats(target);
            double def = foeCtx == null ? tU.def()
                    : effDef(target, foeCtx.tech, foeCtx.skills, foeCtx.commanderDef,
                    foeWallLevel, officerActive, bulwarkActive, side == Side.MINE);
            def = Math.max(0, def * (1 - pierce));
            double cm = counterMul(unitId, target);
            double targetAttack = baseAttack(unitId, target) * attackBonus;
            double damagePerAction = targetAttack * cm * 100.0 / (100.0 + 5 * def);
            double hpPer = Math.max(1, foeCtx == null ? tU.hp() : effHp(target, foeCtx.tech));
            int beforeKill = foeArmy.getOrDefault(target, 0);
            double targetHp = hpPer * beforeKill;
            double damage = remainingActions * damagePerAction;
            double appliedDamage = Math.min(damage, targetHp);
            int kills;
            if (damage + 1e-9 >= targetHp) {
                kills = beforeKill;
                remainingActions = Math.max(0, remainingActions - targetHp / damagePerAction);
            } else {
                kills = (int) Math.floor(appliedDamage / hpPer);
                double fraction = appliedDamage / hpPer - kills;
                if (fraction > 0 && random.getAsDouble() < fraction) kills++;
                remainingActions = 0;
            }
            int remainingTarget = beforeKill - kills;
            foeArmy.put(target, remainingTarget);
            report.append(sidePrefix).append(u.name()).append("(").append(count).append(")")
                    .append(firstTarget ? verb(unitId) : "余伤攻击")
                    .append(side == Side.MINE ? "敌" : "我").append(tU.name())
                    .append("(").append(beforeKill).append(")");
            List<String> tags = new ArrayList<>();
            if (cm > 1.0001) tags.add("倍率×" + cm + " 相克");
            else if (cm < 0.9999) tags.add("倍率×" + cm + " 火力受限");
            if ("htank".equals(target)) tags.add("前排承伤");
            if (firstTarget && focusTarget != null && focusTarget.equals(target)) tags.add("指定集火");
            if (pierce > 0) tags.add("破甲" + percent(pierce) + "%");
            if (!tags.isEmpty()) {
                report.append(" [").append(String.join(" ", tags)).append("]");
            }
            report.append(" ").append(BattleRules.domainLabel(target)).append("攻击").append(Math.round(targetAttack));
            if (firstTarget) report.append(" 本次原始火力").append(Math.round(targetAttack * totalActions));
            report.append(" 伤害").append(Math.round(appliedDamage)).append(" 击毁").append(kills)
                    .append(" 剩余攻击额度").append(Math.round(100 * remainingActions / totalActions)).append("%\n");

            // --- 绝境反击 (Counterattack) ---
            // 调整为在第 3, 6, 9... 回合 (officerActive) 触发反击，反击伤害为剩余兵力总伤害的 10%/级 (满级 50%)
            if (officerActive && !isCounter && foeCtx != null && remainingTarget > 0 && myArmy.getOrDefault(unitId, 0) > 0) {
                double counterRate = skillBonus(foeCtx.skills, "counter");
                if (counterRate > 0 && baseAttack(target, unitId) > 0) {
                    int targetRange = effectiveRange(target, foeCtx);
                    int dist = getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist);
                    if (dist <= targetRange) {
                        Side foeSide = (side == Side.MINE ? Side.ENEMY : Side.MINE);
                        double cAtkBonus = attackBonus(tU.cat(), foeCtx.tech, foeCtx.skills, foeCtx.commanderMil,
                                officerActive, frenzyActive);
                        if (actCtx != null) cAtkBonus *= 1 - skillBonus(actCtx.skills, "suppress");
                        double cPierce = skillBonus(foeCtx.skills, "pierce");
                        double cDef = actCtx == null ? u.def()
                                : effDef(unitId, actCtx.tech, actCtx.skills, actCtx.commanderDef,
                                myWallLevel, officerActive, bulwarkActive, foeSide == Side.MINE);
                        cDef = Math.max(0, cDef * (1 - cPierce));
                        double cCm = counterMul(target, unitId);
                        double cBaseAtk = baseAttack(target, unitId) * cAtkBonus;
                        double cDamagePerAction = cBaseAtk * cCm * 100.0 / (100.0 + 5 * cDef);
                        double cHpPer = Math.max(1, actCtx == null ? u.hp() : effHp(unitId, actCtx.tech));
                        int cBeforeKill = myArmy.getOrDefault(unitId, 0);
                        double cTotalDamage = remainingTarget * cDamagePerAction * counterRate;
                        double cAppliedDamage = Math.min(cTotalDamage, cHpPer * cBeforeKill);
                        int cKills;
                        if (cTotalDamage + 1e-9 >= cHpPer * cBeforeKill) {
                            cKills = cBeforeKill;
                        } else {
                            cKills = (int) Math.floor(cAppliedDamage / cHpPer);
                            double cFrac = cAppliedDamage / cHpPer - cKills;
                            if (cFrac > 0 && random.getAsDouble() < cFrac) cKills++;
                        }
                        myArmy.put(unitId, cBeforeKill - cKills);

                        String foeSidePrefix = foeSide == Side.MINE ? "我方" : "敌方";
                        String mySidePrefix = side == Side.MINE ? "我" : "敌";
                        report.append(foeSidePrefix).append(tU.name()).append("(").append(remainingTarget).append(")")
                                .append(" [绝境反击] ").append(mySidePrefix).append(u.name()).append("(").append(cBeforeKill).append(")");
                        List<String> cTags = new ArrayList<>();
                        if (cCm > 1.0001) cTags.add("倍率×" + cCm + " 相克");
                        else if (cCm < 0.9999) cTags.add("倍率×" + cCm + " 火力受限");
                        if (cPierce > 0) cTags.add("破甲" + percent(cPierce) + "%");
                        if (!cTags.isEmpty()) {
                            report.append(" [").append(String.join(" ", cTags)).append("]");
                        }
                        report.append(" 伤害").append(Math.round(cAppliedDamage)).append(" 击毁").append(cKills).append("\n");

                        // 若发起攻击的单位被反击全歼，则无法继续攻击其他目标
                        if (myArmy.getOrDefault(unitId, 0) <= 0) {
                            break;
                        }
                    }
                }
            }

            firstTarget = false;
            target = remainingActions > 1e-9
                    ? pickCombatTarget(unitId, side, foeArmy, minePos, enemyPos, initialDist, actCtx, null) : null;
        }
    }

    /**
     * 兼容反射调用: 单个距离参数版本的 simAct
     */
    private int simAct(String unitId,
                       Map<String, Integer> myArmy,
                       Map<String, Integer> foeArmy,
                       int dist,
                       StringBuilder report,
                       Side side,
                       TechCtx actCtx,
                       TechCtx foeCtx,
                       int foeWallLevel,
                       boolean officerActive) {
        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        int initialDist = dist;
        for (String k : myArmy.keySet()) {
            if (side == Side.MINE) minePos.put(k, 0);
            else enemyPos.put(k, initialDist);
        }
        for (String k : foeArmy.keySet()) {
            if (side == Side.MINE) enemyPos.put(k, initialDist);
            else minePos.put(k, 0);
        }
        simAct(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side, actCtx, foeCtx, foeWallLevel, officerActive);
        return minDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
    }

    /**
     * 兼容反射调用: 完整坐标参数版本的 simAct
     */
    private boolean simAct(String unitId,
                           Map<String, Integer> myArmy,
                           Map<String, Integer> foeArmy,
                           Map<String, Integer> minePos,
                           Map<String, Integer> enemyPos,
                           int initialDist,
                           StringBuilder report,
                           Side side,
                           TechCtx actCtx,
                           TechCtx foeCtx,
                           int foeWallLevel,
                           boolean officerActive) {
        UnitStats u = getStats(unitId);
        if (u == null) return false;
        int count = myArmy.getOrDefault(unitId, 0);
        if (count <= 0) return false;

        String target = pickCombatTarget(unitId, side, foeArmy, minePos, enemyPos, initialDist, actCtx, null);
        if (target == null) {
            int minDist = minDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
            if (minDist == Integer.MAX_VALUE) return false;
            if (!u.autoAdvance()) return false;
            double spd = actCtx != null ? effSpd(unitId, actCtx.tech, actCtx.skills) : u.spd();
            int step = Math.min((int) (spd * 50), Math.max(0, minDist - effectiveRange(unitId, actCtx)));
            if (step <= 0) return false;
            if (side == Side.MINE) {
                minePos.put(unitId, minePos.getOrDefault(unitId, 0) + step);
            } else {
                enemyPos.put(unitId, enemyPos.getOrDefault(unitId, initialDist) - step);
            }
            int newDist = minDist - step;
            String sidePrefix = side == Side.MINE ? "我方" : "敌方";
            report.append(sidePrefix).append(u.name()).append("(").append(count)
                    .append(") 前进 ").append(step).append(" 距离->").append(newDist).append("\n");
            return true;
        }

        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side, actCtx, foeCtx, foeWallLevel, officerActive, null);
        return false;
    }

    // ========================================================================
    // 有效属性计算 (对应 JS effAtk / effDef / effHp / effSpd)
    // ========================================================================

    /** 概览战力使用对部队的最高攻击；攻坚单独定标，不用于提升经验与概览战力。 */
    private double effAtk(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderMil, boolean officerActive) {
        UnitStats u = getStats(unitId);
        if (u == null) return 0;
        return u.peakTroopAttack() * attackBonus(u.cat(), tech, skills, commanderMil,
                officerActive, officerActive);
    }

    /** 加成对四项攻击采用同一规则；弱项火力也按自身面板增益，不能借用主武器攻击。 */
    private double attackBonus(String cat, Map<String, Integer> tech, Map<String, Integer> skills,
                               int commanderMil, boolean commanderActive, boolean frenzyActive) {
        double multiplier = atkMul(cat, tech, commanderActive ? commanderMil : 0);
        return frenzyActive ? multiplier * (1 + skillBonus(skills, "frenzy")) : multiplier;
    }

    private double baseAttack(String attacker, String target) {
        UnitStats stats = getStats(attacker);
        if (stats == null) return 0;
        return switch (BattleRules.domain(target)) {
            case "air" -> stats.atkAir();
            case "sea" -> stats.atkSea();
            case "fort" -> stats.atkFort();
            default -> stats.atkGround();
        };
    }

    /** 防御科技双方常驻，城墙只加守城方；防御属性和坚守阵地按各自回合生效。 */
    private double effDef(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderDef, int wallLevel, boolean commanderActive,
                          boolean bulwarkActive, boolean defendingCity) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        double multiplier = defMul(u.cat(), tech, wallLevel, defendingCity, commanderActive ? commanderDef : 0);
        if (bulwarkActive) multiplier *= 1 + skillBonus(skills, "bulwark");
        return u.def() * multiplier;
    }

    /**
     * 有效生命值 - 对应 JS effHp(id)
     * <p>
     * 始终生效 (不依赖 officerActive): base * hpMul(cat)
     */
    private double effHp(String unitId, Map<String, Integer> tech) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        return u.hp() * hpMul(u.cat(), tech);
    }

    /**
     * 有效速度 - 对应 JS effSpd(id)
     * <p>
     * 始终生效: base * spdMul(cat) * (1 + blitz)
     */
    private double effSpd(String unitId, Map<String, Integer> tech, Map<String, Integer> skills) {
        UnitStats u = getStats(unitId);
        if (u == null) return 0;
        double base = u.spd() * spdMul(u.cat(), tech);
        double blitz = skillBonus(skills, "blitz");
        return base * (1 + blitz);
    }

    // ========================================================================
    // 倍率计算 (对应 JS Core.atkMul / defMul / hpMul / spdMul)
    // ========================================================================

    /**
     * 攻击倍率 - 对应 JS Core.atkMul(cat)
     * <p>
     * (1 + 0.05*attack_tech) * (1 + cmdMil/100)
     */
    private double atkMul(String cat, Map<String, Integer> tech, int commanderMil) {
        int cmdAttack = tech.getOrDefault("attack_tech", 0);
        double allMul = 1 + 0.05 * cmdAttack;
        return allMul * (1 + commanderMil / 100.0);
    }

    /**
     * 防御倍率 - 对应 JS Core.defMul(cat, isMine)
     * <p>
     * (1 + 0.05*defense_tech) * (1 + cmdDef/100) * wallMul
     * wallMul = (isMine && cat != 'air') ? (1 + 0.05*wall) : 1
     */
    private double defMul(String cat, Map<String, Integer> tech, int wallLevel, boolean isMine) {
        return defMul(cat, tech, wallLevel, isMine, 0);
    }

    private double defMul(String cat, Map<String, Integer> tech, int wallLevel, boolean isMine, int commanderDef) {
        int cmdDefense = tech.getOrDefault("defense_tech", 0);
        double allMul = 1 + 0.05 * cmdDefense;
        if (commanderDef > 0) {
            allMul *= (1 + commanderDef / 100.0);
        }
        double catMul = 1;
        double wallMul = (isMine && !"air".equals(cat)) ? (1 + 0.05 * wallLevel) : 1;
        return allMul * catMul * wallMul;
    }

    /**
     * 生命倍率 - 对应 JS Core.hpMul(cat)
     * <p>
     * 1 + 0.05*cmd_hp
     */
    private double hpMul(String cat, Map<String, Integer> tech) {
        int cmdHp = tech.getOrDefault("cmd_hp", 0);
        return 1 + 0.05 * cmdHp;
    }

    /**
     * 速度倍率 - 对应 JS Core.spdMul(cat)
     * <p>
     * inf: 1 (无引擎科技)
     * arm: 1 + 0.05*arm_engine
     * air: 1 + 0.05*air_engine
     * nav: 1 + 0.05*nav_engine
     */
    private double spdMul(String cat, Map<String, Integer> tech) {
        String catKey = switch (cat) {
            case "arm" -> "arm_engine";
            case "air" -> "air_engine";
            case "nav" -> "nav_engine";
            default -> null;
        };
        if (catKey == null) return 1;
        return 1 + 0.05 * tech.getOrDefault(catKey, 0);
    }

    // ========================================================================
    // 技能加成 (对应 JS Core.skillBonus)
    // ========================================================================

    /**
     * 技能加成值 - 对应 JS Core.skillBonus(skillId)
     * <p>
     * 返回 rate * level, rate 来自 SKILL_RATES
     */
    private double skillBonus(Map<String, Integer> skills, String skillId) {
        if (skills == null) return 0;
        int lv = skills.getOrDefault(skillId, 0);
        if (lv <= 0) return 0;
        if ("counter".equals(skillId)) {
            // 方案 A+：初始 20% 概率，每级 +8%，满级 52%
            return 0.12 + 0.08 * Math.min(5, lv);
        }
        double rate = SKILL_RATES.getOrDefault(skillId, 0.0);
        return rate * Math.min(5, lv);
    }

    private String buildCommanderBonusLog(String sideName, TechCtx ctx, int round, boolean isAttacker) {
        boolean officerActive = round % 3 == 0;
        boolean frenzyActive = round % 3 == 1;
        boolean bulwarkActive = round % 3 == 2;
        List<String> bonuses = new ArrayList<>();
        if (officerActive || frenzyActive || bulwarkActive) {
            if (ctx.commanderMil > 0) {
                if (officerActive) bonuses.add("军事属性 +" + ctx.commanderMil + "%攻击");
            }
            if (ctx.commanderDef > 0) {
                if (officerActive) bonuses.add("防御属性 +" + ctx.commanderDef + "%防御");
            }
            if (frenzyActive) appendSkillBonus(bonuses, ctx.skills, "frenzy", "全军冲锋", "+", "攻击");
            appendSkillBonus(bonuses, ctx.skills, "suppress", "火力压制", "-", "敌方攻击");
            if (bulwarkActive) appendSkillBonus(bonuses, ctx.skills, "bulwark", "坚守阵地", "+", "防御");
            appendSkillBonus(bonuses, ctx.skills, "blitz", "闪电突击", "+", "速度（持续生效）");
            return sideName + "将领加成：" + (bonuses.isEmpty() ? "本回合无将领属性或技能加成生效" : String.join("；", bonuses));
        }

        return "";
    }

    private void appendSkillBonus(List<String> bonuses, Map<String, Integer> skills, String skillId,
                                  String name, String prefix, String effect) {
        double bonus = skillBonus(skills, skillId);
        if (bonus > 0) bonuses.add(name + " " + prefix + percent(bonus) + "%" + effect);
    }

    private int percent(double value) {
        return (int) Math.round(value * 100);
    }

    // ========================================================================
    // 战斗辅助方法
    // ========================================================================

    /** 行动方枚举 */
    private enum Side { MINE, ENEMY }

    /** 科技/技能上下文 */
    public record TechCtx(Map<String, Integer> tech, Map<String, Integer> skills, int commanderMil, int commanderDef) {
        public TechCtx(Map<String, Integer> tech, Map<String, Integer> skills, int commanderMil) {
            this(tech, skills, commanderMil, 0);
        }
    }

    /** 行动序列条目 */
    private record ActionEntry(Side side, String id, int range, double spd) {}

    /**
     * 构建行动序列 - 对应 JS Battle.buildOrder
     * <p>
     * 按射程降序、速度降序排序
     */
    private List<ActionEntry> buildOrder(Map<String, Integer> mine, Map<String, Integer> enemy,
                                         TechCtx mineCtx, TechCtx foeCtx, int round) {
        List<ActionEntry> arr = new ArrayList<>();

        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = mineCtx != null
                    ? effSpd(e.getKey(), mineCtx.tech, mineCtx.skills)
                    : u.spd();
            arr.add(new ActionEntry(Side.MINE, e.getKey(), effectiveRange(e.getKey(), mineCtx), spd));
        }
        for (Map.Entry<String, Integer> e : enemy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = foeCtx != null
                    ? effSpd(e.getKey(), foeCtx.tech, foeCtx.skills)
                    : u.spd();
            arr.add(new ActionEntry(Side.ENEMY, e.getKey(), effectiveRange(e.getKey(), foeCtx), spd));
        }

        // 排序: 射程降序, 速度降序 - 对应 JS arr.sort
        arr.sort((a, b) -> {
            if (b.range != a.range) return b.range - a.range;
            int speedOrder = Double.compare(b.spd, a.spd);
            if (speedOrder != 0) return speedOrder;
            // 同射程同速度轮换先手；同侧按 ID 排序，结果不依赖 Map 的遍历顺序。
            if (a.side != b.side) return a.side == (round % 2 == 1 ? Side.MINE : Side.ENEMY) ? -1 : 1;
            return a.id.compareTo(b.id);
        });
        return arr;
    }

    /** 具体兵种/目标类别决定倍率，旧 strongVs 只保留为代表性克制标签。 */
    private double counterMul(String attackerId, String defenderId) {
        return BattleRules.multiplier(attackerId, defenderId);
    }

    /**
     * 单位经验价值 - 对应 JS unitExpValue
     * <p>
     * floor((hp + atk*3 + def*2) / 10) + 1
     */
    private int unitExpValue(String unitId) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        return (int) Math.floor((u.hp() + u.peakTroopAttack() * 3 + u.def() * 2) / 10.0) + 1;
    }

    /**
     * 计算击杀经验 - 对应 JS calcKillExp
     * <p>
     * total = sum(killed * unitExpValue(eid))
     */
    private int calcKillExp(Map<String, Integer> enemyKilled) {
        int total = 0;
        for (Map.Entry<String, Integer> e : enemyKilled.entrySet()) {
            int killed = e.getValue();
            if (killed > 0) total += killed * unitExpValue(e.getKey());
        }
        return total;
    }

    /**
     * 最大射程 - 对应 JS maxRangeOf
     */
    private int maxRangeOf(Map<String, Integer> army) {
        int mr = 0;
        for (String id : army.keySet()) {
            UnitStats u = getStats(id);
            if (u != null && u.range() > mr) mr = u.range();
        }
        return mr;
    }

    /**
     * 是否全灭 - 对应 JS allDead
     */
    private boolean allDead(Map<String, Integer> army) {
        for (Integer count : army.values()) {
            if (count != null && count > 0) return false;
        }
        return true;
    }

    /**
     * 计算攻守双方指定单位之间的物理距离。
     * 攻方坐标从 0 开始向右推进，守方坐标从 initialDist 开始向左推进。
     */
    private int getUnitDistToFoe(Side side, String myUnitId, String foeUnitId,
                                Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        int mineX = (side == Side.MINE) ? minePos.getOrDefault(myUnitId, 0) : minePos.getOrDefault(foeUnitId, 0);
        int enemyX = (side == Side.MINE) ? enemyPos.getOrDefault(foeUnitId, initialDist) : enemyPos.getOrDefault(myUnitId, initialDist);
        return Math.max(0, enemyX - mineX);
    }

    /**
     * 仅向能够攻击的目标靠近；没有目标时返回 MAX_VALUE，避免被近处的无效目标卡住。
     */
    private int minDistanceToLivingFoe(Side side, String myUnitId, Map<String, Integer> foeArmy,
                                       Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        int minDist = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : foeArmy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            if (baseAttack(myUnitId, e.getKey()) <= 0) continue;
            int d = getUnitDistToFoe(side, myUnitId, e.getKey(), minePos, enemyPos, initialDist);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    /** 开局前置机制已取消，所有单位开局均从阵地底线出发 (攻方0 / 守方initialDist)。 */
    private int formationOffset(String id) {
        return 0;
    }

    private int effectiveRange(String id, TechCtx ctx) {
        UnitStats u = getStats(id);
        return u == null ? 0 : (int) Math.floor(u.range()
                * (1 + 0.05 * (ctx == null ? 0 : ctx.tech.getOrDefault("weapon_range", 0))));
    }

    /**
     * 先过滤攻击能力、射程与重坦掩护，再按领域攻击×专项倍率选择有利目标，其次距离、生命和 ID。
     * 重坦只能掩护位于其后方的地面单位，不能替空军/舰船挡弹；特种兵可渗透后排。
     */
    private String pickTargetInRange(String attackerId, Side side, Map<String, Integer> foeArmy,
                                     Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                     int initialDist, TechCtx ctx) {
        int range = effectiveRange(attackerId, ctx);
        int tankDistance = foeArmy.getOrDefault("htank", 0) > 0
                ? getUnitDistToFoe(side, attackerId, "htank", minePos, enemyPos, initialDist)
                : Integer.MAX_VALUE;
        String best = null;
        double bestMultiplier = -1;
        int bestDistance = Integer.MAX_VALUE;
        double bestHp = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : foeArmy.entrySet()) {
            String id = entry.getKey();
            UnitStats target = getStats(id);
            if (target == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            if (baseAttack(attackerId, id) <= 0) continue;
            int distance = getUnitDistToFoe(side, attackerId, id, minePos, enemyPos, initialDist);
            if (distance > range) continue;
            if (!"special".equals(attackerId) && !"htank".equals(id) && BattleRules.ground(id)
                    && tankDistance <= distance && tankDistance <= range) continue;
            double multiplier = baseAttack(attackerId, id) * counterMul(attackerId, id);
            if (multiplier > bestMultiplier
                    || (multiplier == bestMultiplier && distance < bestDistance)
                    || (multiplier == bestMultiplier && distance == bestDistance && target.hp() < bestHp)
                    || (multiplier == bestMultiplier && distance == bestDistance && target.hp() == bestHp
                    && (best == null || id.compareTo(best) < 0))) {
                best = id;
                bestMultiplier = multiplier;
                bestDistance = distance;
                bestHp = target.hp();
            }
        }
        return best;
    }

    /**
     * 是否有任何单位能够攻击到敌方存活单位 (独立坐标版本)
     */
    private boolean anyInRange(Map<String, Integer> mine, Map<String, Integer> enemy,
                              Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            for (Map.Entry<String, Integer> fe : enemy.entrySet()) {
                if (fe.getValue() == null || fe.getValue() <= 0) continue;
                int d = getUnitDistToFoe(Side.MINE, e.getKey(), fe.getKey(), minePos, enemyPos, initialDist);
                if (u.range() >= d) return true;
            }
        }
        for (Map.Entry<String, Integer> e : enemy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            for (Map.Entry<String, Integer> fe : mine.entrySet()) {
                if (fe.getValue() == null || fe.getValue() <= 0) continue;
                int d = getUnitDistToFoe(Side.ENEMY, e.getKey(), fe.getKey(), minePos, enemyPos, initialDist);
                if (u.range() >= d) return true;
            }
        }
        return false;
    }

    /**
     * 是否有单位在射程内 - 对应 JS anyInRange
     */
    private boolean anyInRange(Map<String, Integer> mine, Map<String, Integer> enemy, int dist) {
        for (String id : mine.keySet()) {
            UnitStats u = getStats(id);
            if (mine.getOrDefault(id, 0) > 0 && u != null && u.range() >= dist) return true;
        }
        for (String id : enemy.keySet()) {
            UnitStats u = getStats(id);
            if (enemy.getOrDefault(id, 0) > 0 && u != null && u.range() >= dist) return true;
        }
        return false;
    }

    /**
     * 仓库保护上限 - 对应 JS Core.protectCap
     * <p>
     * floor(depotLv * protectPer)
     */
    private int calcProtectCap(long depotLevel) {
        BuildingDef depot = GameData.BUILDINGS.get("depot");
        int protectPer = depot != null && depot.protectPer() != null ? depot.protectPer() : 1000;
        return (int) Math.floor(depotLevel * protectPer);
    }

    /**
     * 动作词映射 - 对应 JS Battle.verb
     */
    private String verb(String id) {
        return switch (id) {
            case "infantry" -> "射击";
            case "motor" -> "冲击";
            case "truck" -> "碾压";
            case "armored" -> "扫射";
            case "ltank", "htank", "destroyer", "howitzer" -> "炮击";
            case "assault" -> "拦射";
            case "rocket" -> "齐射";
            case "scout" -> "侦察";
            case "special" -> "突袭";
            case "fighter" -> "空战";
            case "bomber" -> "轰炸";
            case "transport" -> "空投";
            case "sub" -> "雷击";
            case "battleship" -> "舰炮齐射";
            case "carrier" -> "舰载机打击";
            case "bunker" -> "扫射";
            case "antitank" -> "穿甲";
            case "flak" -> "对空";
            default -> "攻击";
        };
    }

    /** 克隆 map - 对应 JS snapshot */
    private Map<String, Integer> snapshot(Map<String, Integer> src) {
        Map<String, Integer> dst = new LinkedHashMap<>();
        if (src != null) {
            for (Map.Entry<String, Integer> e : src.entrySet()) {
                dst.put(e.getKey(), e.getValue());
            }
        }
        return dst;
    }

    /** 过滤正数条目 */
    private Map<String, Integer> filterPositive(Map<String, Integer> map) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /** 构建 resolveWild 结果 */
    private BattleResult buildWildResult(boolean win,
                                         Map<String, Integer> myArmy, Map<String, Integer> foeArmy,
                                         Map<String, Integer> myStart, Map<String, Integer> foeStart,
                                         StringBuilder report) {
        Map<String, Integer> survivorAttacker = filterPositive(myArmy);
        Map<String, Integer> survivorDefender = filterPositive(foeArmy);
        Map<String, Integer> emptyPlunder = new LinkedHashMap<>();
        for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
            emptyPlunder.put(rk, 0);
        }
        // 计算击杀经验 - 对应 JS calcKillExp
        Map<String, Integer> foeKilled = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : foeStart.entrySet()) {
            int start = e.getValue() != null ? e.getValue() : 0;
            int now = foeArmy.getOrDefault(e.getKey(), 0);
            int killed = start - now;
            if (killed > 0) foeKilled.put(e.getKey(), killed);
        }
        int exp = calcKillExp(foeKilled);
        // 败战经验减为 30% - 对应 JS finish 中 _officerExpGain
        if (!win) exp = (int) Math.floor(exp * 0.3);

        Map<String, Integer> initialAttacker = filterPositive(myStart);
        Map<String, Integer> initialDefender = filterPositive(foeStart);
        return new BattleResult(win, survivorAttacker, survivorDefender,
                initialAttacker, initialDefender,
                emptyPlunder, exp, report.toString(), false);
    }

    // ========================================================================
    // 公开辅助方法 (供外部调用)
    // ========================================================================

    /**
     * 计算单位类型的总战力 - 综合考虑基础属性、科技和军官技能。
     * <p>
     * 对应 JS 中 effAtk/effDef * count 的计算逻辑。
     *
     * @param unitType 单位类型
     * @param count    数量
     * @param tech     科技等级 (techKey -> level)
     * @param skills   军官技能 (skillId -> level)
     * @param isAttack true=计算攻击力, false=计算防御力
     * @return 有效战力
     */
    public double calcUnitPower(String unitType, int count, Map<String, Integer> tech,
                                Map<String, Integer> skills, boolean isAttack) {
        UnitStats u = getStats(unitType);
        if (u == null || count <= 0) return 0;

        Map<String, Integer> t = tech != null ? tech : Collections.emptyMap();
        Map<String, Integer> s = skills != null ? skills : Collections.emptyMap();

        if (isAttack) {
            // 攻击力: 使用军官生效时的最大值
            double atk = effAtk(unitType, t, s, 0, true);
            return atk * count;
        } else {
            // 防御力: 使用军官生效时的最大值 (isMine=true)
            double def = effDef(unitType, t, s, 0, 0, true, true, true);
            return def * count;
        }
    }

    /**
     * 计算城防总防御加成 - 从城防类型和数量计算。
     * <p>
     * 对应 JS 中城防作为守方单位参与战斗时的防御贡献。
     *
     * @param forts 城防 (fortType -> count)
     * @return 总防御加成
     */
    public int calcFortBonus(Map<String, Integer> forts) {
        if (forts == null) return 0;
        int total = 0;
        for (Map.Entry<String, Integer> e : forts.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            FortDef f = GameData.FORTS.get(e.getKey());
            if (f != null) {
                total += f.def() * e.getValue();
            }
        }
        return total;
    }

    /**
     * 按损失比例削减军队 - 返回新的 map (不修改原始)。
     * <p>
     * 对应 JS finish 中失败时的残部回收逻辑。
     *
     * @param army      原始军队
     * @param lossRatio 损失比例 (0.0 ~ 1.0)
     * @return 削减后的军队
     */
    public Map<String, Integer> applyLosses(Map<String, Integer> army, double lossRatio) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (army == null) return result;
        double surviveRatio = 1.0 - lossRatio;
        for (Map.Entry<String, Integer> e : army.entrySet()) {
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count > 0) {
                result.put(e.getKey(), (int) Math.floor(count * surviveRatio));
            }
        }
        return result;
    }

    /**
     * 计算可掠夺资源 - 仓库保护部分资源, 其余可被掠夺。
     * <p>
     * 对应 JS finish 中 plunder 分支的 protectCap 逻辑。
     *
     * @param defenderResources       守方资源 (food/steel/oil/rare/gold -> amount)
     * @param defenderWarehouseLevel  守方仓库等级
     * @return 可掠夺资源
     */
    public Map<String, Integer> calcPlunder(Map<String, Integer> defenderResources,
                                            long defenderWarehouseLevel) {
        Map<String, Integer> plunder = new LinkedHashMap<>();
        if (defenderResources == null) {
            for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                plunder.put(rk, 0);
            }
            return plunder;
        }

        int protection = calcProtectCap(defenderWarehouseLevel);

        // food/steel/oil/rare: 仓库保护, 取超出部分
        for (String rk : List.of("food", "steel", "oil", "rare")) {
            int amount = defenderResources.getOrDefault(rk, 0);
            plunder.put(rk, Math.max(0, amount - protection));
        }
        // gold: 不受仓库保护
        plunder.put("gold", defenderResources.getOrDefault("gold", 0));

        return plunder;
    }
}
