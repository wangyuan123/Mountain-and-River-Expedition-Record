package com.wargame.service;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗服务 - 对应 JS G.Battle 中的核心战斗逻辑。
 * <p>
 * 战斗公式 (来自 JS simAct):
 * <pre>
 *   dmg = (atk * atk * count * cm * closeMul) / (def * 10)
 *   remainingDamage = dmg  // 单次行动只计算一次总伤害
 *   appliedDamage = min(remainingDamage, targetCount * hpPer)
 *   remainingDamage -= appliedDamage
 *   // 当前目标全灭且仍有余伤时，继续攻击下一个目标；不足一单位的伤害按概率结算。
 * </pre>
 * 其中:
 * <ul>
 *   <li>atk = effAtk(unitId) - 有效攻击力</li>
 *   <li>count = 攻击方该兵种数量</li>
 *   <li>cm = counterMul - 首个目标的相克倍率 (strongVs 时 1.5, 否则 1.0)</li>
 *   <li>closeMul = 贴脸倍率 (距离 0 时 2.0, 否则 1.0)</li>
 *   <li>def = effDef(target) - 首个目标的有效防御力 (被破甲技能削减, 最低 1)</li>
 *   <li>hpPer = effHp(target) - 每单位有效生命值</li>
 * </ul>
 */
@Service
public class BattleService {

    private static final int MAX_ROUND = 30;

    /** NPC/野地战斗初始固定距离 */
    public static final int DISTANCE_NPC = 2200;
    /** 玩家之间战斗初始固定距离 */
    public static final int DISTANCE_PLAYER = 3000;

    /** 军官技能每级加成比率 - 对应 JS Core.skillBonus 中的 rates */
    private static final Map<String, Double> SKILL_RATES = Map.of(
            "frenzy", 0.10,
            "bulwark", 0.10,
            "blitz", 0.15,
            "suppress", 0.08,
            "pierce", 0.12,
            "supply", 0.20,
            "medic", 0.15,
            "combo", 0.08
    );

    // ========================================================================
    // 内部数据结构
    // ========================================================================

    /** 统一单位/城防属性 - 对应 JS U(id) 返回值的常用字段 */
    private record UnitStats(String key, String name, String cat,
                             int atk, int def, int hp, int spd, int range,
                             String strongVs, boolean autoAdvance) {}

    /** 从 UnitDef 或 FortDef 获取统一属性 - 对应 JS U(id) */
    private UnitStats getStats(String id) {
        if (id == null) return null;
        UnitDef u = GameData.UNITS.get(id);
        if (u != null) {
            return new UnitStats(u.key(), u.name(), u.cat(),
                    u.atk(), u.def(), u.hp(), u.spd(), u.range(),
                    u.strongVs(), u.autoAdvance() == null || u.autoAdvance());
        }
        FortDef f = GameData.FORTS.get(id);
        if (f != null) {
            return new UnitStats(f.key(), f.name(), f.cat(),
                    f.atk(), f.def(), f.hp(), f.spd(), f.range(),
                    f.strongVs(), f.autoAdvance());
        }
        return null;
    }

    // ========================================================================
    // resolveWild - 野地战斗 (无科技/军官加成, 使用基础属性)
    // ========================================================================

    /**
     * 解析野地战斗 - 对应 JS G.Battle.resolveWild(garrison, mine)。
     * <p>
     * 野地战斗不设战场上下文 (ctx 为 null), 因此 effAtk/effDef 返回基础值,
     * 仅使用兵种原生属性进行模拟。
     *
     * @param garrison 守方驻军 (unitType -> count)
     * @param attacker 攻方军队 (unitType -> count)
     * @return 战斗结果, 包含幸存攻方单位和胜负
     */
    public BattleResult resolveWild(Map<String, Integer> garrison, Map<String, Integer> attacker) {
        Map<String, Integer> myArmy = snapshot(attacker);
        Map<String, Integer> foeArmy = snapshot(garrison);
        Map<String, Integer> myStart = snapshot(myArmy);
        Map<String, Integer> foeStart = snapshot(foeArmy);

        int initialDist = DISTANCE_NPC;
        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : myArmy.keySet()) minePos.put(k, 0);
        for (String k : foeArmy.keySet()) enemyPos.put(k, initialDist);

        StringBuilder report = new StringBuilder();

        for (int round = 1; round <= MAX_ROUND; round++) {
            List<ActionEntry> order = buildOrder(myArmy, foeArmy, null, null);
            boolean moved = false;
            for (ActionEntry a : order) {
                Map<String, Integer> myA = a.side == Side.MINE ? myArmy : foeArmy;
                Map<String, Integer> foe = a.side == Side.MINE ? foeArmy : myArmy;
                if (myA.getOrDefault(a.id, 0) <= 0) continue;
                if (allDead(foe)) break;
                boolean actionMoved = simAct(a.id, myA, foe, minePos, enemyPos, initialDist, report, a.side,
                        null, null, 0, false);
                if (actionMoved) moved = true;
            }





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
                attackerCommanderMil, defenderCommanderMil,
                attackerWallLevel, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel, false);
    }

    /**
     * 解析世界出征战斗 (支持区分 NPC 战斗与玩家对战)。
     *
     * @param isPlayerBattle 是否为玩家对战 (true: 初始距离 3000, false: 初始距离 2200)
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

        // 保存初始军队快照 (用于战后经验计算) - 对应 JS mineStart / enemyStart
        Map<String, Integer> mineStart = snapshot(mine);
        Map<String, Integer> enemyStart = snapshot(enemy);

        // 战场初始距离: NPC战斗固定2200, 玩家对战固定3000
        int initialDist = isPlayerBattle ? DISTANCE_PLAYER : DISTANCE_NPC;
        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : mine.keySet()) minePos.put(k, 0);
        for (String k : enemy.keySet()) enemyPos.put(k, initialDist);

        StringBuilder report = new StringBuilder();

        // 模拟 30 回合 - 对应 JS resolveRound 循环
        for (int round = 1; round <= MAX_ROUND; round++) {
            // 军官每 3 回合生效 - 对应 JS ctx.round % 3 === 0
            boolean officerActive = (round % 3 == 0);

            if (officerActive) {
                report.append("-- 第").append(round).append("回合 (军官加成生效) --\n");
            } else {
                report.append("-- 第").append(round).append("回合 --\n");
            }

            TechCtx attackerCtx = new TechCtx(aTech, aSkills, attackerCommanderMil);
            TechCtx defenderCtx = new TechCtx(dTech, dSkills, defenderCommanderMil);
            String mineBonusLog = buildCommanderBonusLog("我方", attackerCtx, officerActive, true);
            String foeBonusLog = buildCommanderBonusLog("敌方", defenderCtx, officerActive, false);
            if (!mineBonusLog.isEmpty()) report.append(mineBonusLog).append("\n");
            if (!foeBonusLog.isEmpty()) report.append(foeBonusLog).append("\n");

            // 构建行动序列 - 对应 JS buildOrder
            List<ActionEntry> order = buildOrder(mine, enemy, attackerCtx, defenderCtx);

            boolean moved = false;
            for (ActionEntry a : order) {
                Map<String, Integer> myA = a.side == Side.MINE ? mine : enemy;
                Map<String, Integer> foe = a.side == Side.MINE ? enemy : mine;
                if (myA.getOrDefault(a.id, 0) <= 0) continue;
                if (allDead(foe)) break;

                // 行动方科技上下文
                TechCtx actCtx = a.side == Side.MINE
                        ? new TechCtx(aTech, aSkills, attackerCommanderMil)
                        : new TechCtx(dTech, dSkills, defenderCommanderMil);
                // 防守方科技上下文 (目标所属方, 与行动方相反)
                TechCtx foeCtx = a.side == Side.MINE
                        ? new TechCtx(dTech, dSkills, defenderCommanderMil)
                        : new TechCtx(aTech, aSkills, attackerCommanderMil);
                int foeWallLevel = a.side == Side.MINE ? defenderWallLevel : attackerWallLevel;

                boolean actionMoved = simAct(a.id, myA, foe, minePos, enemyPos, initialDist, report, a.side,
                        actCtx, foeCtx, foeWallLevel, officerActive);
                if (actionMoved) moved = true;
            }







            // 胜负判定 - 对应 JS resolveRound 中的判定
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

        // 幸存攻方单位
        Map<String, Integer> survivorAttacker;
        if (win) {
            // 胜利时攻方幸存 = 当前剩余单位 - 对应 JS finish win 分支
            survivorAttacker = filterPositive(mine);
        } else {
            // 失败时残部回收: survRate = 0.5 + medicalMul - 对应 JS finish else 分支
            double survRate = 0.5 + medicalMul(attackerTech);
            survivorAttacker = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : mine.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    survivorAttacker.put(e.getKey(), (int) Math.floor(e.getValue() * survRate));
                }
            }
            report.append("残部回收率: ").append((int) Math.floor(survRate * 100)).append("%\n");
        }

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

    /**
     * 模拟一个单位的行动: 移动或攻击 (各兵种独立计算坐标与距离)。
     *
     * @return 该单位是否产生位移前进
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

        // 速度计算 - 对应 JS simAct 中 spd 的取值
        double spd;
        if (actCtx != null) {
            spd = effSpd(unitId, actCtx.tech, actCtx.skills);
        } else {
            spd = u.spd(); // resolveWild 场景: 基础速度
        }

        String sidePrefix = side == Side.MINE ? "我方" : "敌方";

        // 寻找射程内的攻击目标
        String target = pickTargetInRange(unitId, side, foeArmy, minePos, enemyPos, initialDist);

        // 射程外: 移动阶段
        if (target == null) {
            int minDist = minDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
            if (!u.autoAdvance()) {
                // 不主动前进的单位(如卡车、运输机、城防设施等)在射程外待命，不主动冲锋
                report.append(sidePrefix).append(u.name())
                        .append("(").append(count).append(")")
                        .append(" 待命 距离").append(minDist).append("\n");
                return false;
            }

            int step = Math.min((int) (spd * 50), minDist);
            if (step <= 0) return false;

            if (side == Side.MINE) {
                int curPos = minePos.getOrDefault(unitId, 0);
                minePos.put(unitId, curPos + step);
            } else {
                int curPos = enemyPos.getOrDefault(unitId, initialDist);
                enemyPos.put(unitId, curPos - step);
            }
            int newDist = minDist - step;
            report.append(sidePrefix).append(u.name())
                    .append("(").append(count).append(")")
                    .append(" 前进 ").append(step).append(" 距离->").append(newDist).append("\n");
            return true;
        }

        // 射程内: 攻击阶段 (前进0)
        int targetDist = getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist);
        report.append(sidePrefix).append(u.name())
                .append("(").append(count).append(")")
                .append(" 前进0(射程内) 距离").append(targetDist).append("\n");

        int baseAtk = u.atk();

        // 有效攻击力 - 对应 JS effAtk
        double atk;
        if (actCtx != null) {
            atk = effAtk(unitId, actCtx.tech, actCtx.skills, actCtx.commanderMil, officerActive);
        } else {
            atk = baseAtk; // resolveWild 场景: 基础攻击力
        }

        // 有效防御力 - 对应 JS effDef
        // foeIsMine: 目标是否属于"我方" (JS 中 foeIsMine = side === 'enemy')
        boolean foeIsMine = (side == Side.ENEMY);
        double def;
        if (foeCtx != null) {
            def = effDef(target, foeCtx.tech, foeCtx.skills, foeCtx.commanderMil,
                    foeWallLevel, officerActive, foeIsMine);
        } else {
            UnitStats tStats = getStats(target);
            def = tStats != null ? tStats.def() : 1;
        }

        // 破甲技能 - 对应 JS pierce (仅攻方 mine 侧生效)
        double pierce = 0;
        if (actCtx != null && side == Side.MINE) {
            pierce = skillBonus(actCtx.skills, "pierce");
        }
        if (pierce > 0) def = def * (1 - pierce);
        if (def < 1) def = 1;

        // 相克倍率 - 对应 JS counterMul
        double cm = counterMul(unitId, target);

        // 贴脸倍率 - 对应 JS closeMul (与目标距离 0 时 ×2)
        double closeMul = (targetDist == 0) ? 2.0 : 1.0;

        // 伤害公式 - 对应 JS dmg = (atk * atk * count * cm * closeMul) / (def * 10)
        double dmg = (atk * atk * count * cm * closeMul) / (def * 10);

        // 连击技能 - 对应 JS combo (仅攻方 mine 侧生效)
        double comboRate = 0;
        if (actCtx != null && side == Side.MINE) {
            comboRate = skillBonus(actCtx.skills, "combo");
        }
        boolean comboHit = comboRate > 0 && ThreadLocalRandom.current().nextDouble() < comboRate;
        if (comboHit) dmg *= 2;

        StringBuilder bonusTag = new StringBuilder();
        if (side == Side.MINE && baseAtk > 0 && atk / baseAtk > 1.01) {
            bonusTag.append(" 军官加成×").append(String.format("%.2f", atk / baseAtk));
        }
        if (pierce > 0) bonusTag.append(" 破甲").append((int) Math.round(pierce * 100)).append("%");
        if (comboHit) bonusTag.append(" 连击");
        if (cm > 1) bonusTag.append(" 相克");
        if (closeMul > 1) bonusTag.append(" 贴脸");

        // 一次行动共用固定伤害池。余伤仅攻击仍在自身射程内的敌方存活目标。
        double remainingDamage = dmg;
        boolean firstTarget = true;
        while (target != null && remainingDamage > 0) {
            UnitStats tU = getStats(target);
            double hpPer = foeCtx != null ? effHp(target, foeCtx.tech) : tU.hp();
            hpPer = Math.max(1, hpPer);
            int beforeKill = foeArmy.getOrDefault(target, 0);
            double targetHp = hpPer * beforeKill;
            double appliedDamage = Math.min(remainingDamage, targetHp);

            int kills;
            if (remainingDamage >= targetHp) {
                kills = beforeKill;
                remainingDamage -= targetHp;
            } else {
                // 不足以全灭时沿用概率击杀；本次余伤全部消耗在当前目标上。
                kills = (int) Math.floor(appliedDamage / hpPer);
                double fraction = (appliedDamage % hpPer) / hpPer;
                if (fraction > 0 && ThreadLocalRandom.current().nextDouble() < fraction) kills++;
                remainingDamage = 0;
            }
            foeArmy.put(target, beforeKill - kills);

            report.append(sidePrefix).append(u.name()).append("(").append(count).append(")")
                    .append(firstTarget ? verb(unitId) : "余伤攻击")
                    .append(side == Side.MINE ? "敌" : "我").append(tU.name())
                    .append("(").append(beforeKill).append(")");
            if (firstTarget && bonusTag.length() > 0) report.append(" [").append(bonusTag.toString().trim()).append("]");
            if (firstTarget) report.append(" 本次总伤害").append(Math.round(dmg));
            report.append(" 伤害").append(Math.round(appliedDamage)).append(" 击毁").append(kills);
            report.append(" 剩余伤害").append(Math.round(remainingDamage));
            report.append("\n");

            firstTarget = false;
            target = remainingDamage > 0 ? pickTargetInRange(unitId, side, foeArmy, minePos, enemyPos, initialDist) : null;
        }

        return false;
    }

    /**
     * 兼容重载: 单个距离参数版本的 simAct (用于单元测试直接反射调用等)。
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

    // ========================================================================
    // 有效属性计算 (对应 JS effAtk / effDef / effHp / effSpd)
    // ========================================================================

    /**
     * 有效攻击力 - 对应 JS effAtk(id)
     * <p>
     * 军官生效时: base * atkMul(cat) * (1 + frenzy) * (1 - suppress)
     * 否则: base
     */
    private double effAtk(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderMil, boolean officerActive) {
        UnitStats u = getStats(unitId);
        if (u == null) return 0;
        double base = u.atk();
        if (!officerActive) return base;
        double mul = atkMul(u.cat(), tech, commanderMil);
        double frenzy = skillBonus(skills, "frenzy");
        double suppress = skillBonus(skills, "suppress");
        mul *= (1 + frenzy);
        mul *= (1 - suppress);
        return base * mul;
    }

    /**
     * 有效防御力 - 对应 JS effDef(id, isMine)
     * <p>
     * 军官生效且 isMine 时: base * defMul(cat, isMine) * (1 + bulwark)
     * 否则: base
     */
    private double effDef(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderMil, int wallLevel, boolean officerActive, boolean isMine) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        double base = u.def();
        if (!officerActive || !isMine) return base;
        double mul = defMul(u.cat(), tech, wallLevel, isMine);
        double bulwark = skillBonus(skills, "bulwark");
        mul *= (1 + bulwark);
        return base * mul;
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
     * (1 + 0.05*defense_tech) * wallMul
     * wallMul = (isMine && cat != 'air') ? (1 + 0.05*wall) : 1
     */
    private double defMul(String cat, Map<String, Integer> tech, int wallLevel, boolean isMine) {
        int cmdDefense = tech.getOrDefault("defense_tech", 0);
        double allMul = 1 + 0.05 * cmdDefense;
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

    /** cat -> 攻击科技 key */
    private String catAtkKey(String cat) { return "attack_tech"; }

    /** cat -> 防御科技 key */
    private String catDefKey(String cat) { return "defense_tech"; }

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
        double rate = SKILL_RATES.getOrDefault(skillId, 0.0);
        return rate * lv;
    }

    private String buildCommanderBonusLog(String sideName, TechCtx ctx, boolean officerActive, boolean isAttacker) {
        List<String> bonuses = new ArrayList<>();
        if (officerActive) {
            if (ctx.commanderMil > 0) {
                bonuses.add("军事属性 +" + ctx.commanderMil + "%攻击");
            }
            appendSkillBonus(bonuses, ctx.skills, "frenzy", "猛攻", "+", "攻击");
            appendSkillBonus(bonuses, ctx.skills, "suppress", "压制", "-", "攻击");
            if (isAttacker) {
                appendSkillBonus(bonuses, ctx.skills, "bulwark", "铁壁", "+", "防御");
            }
            appendSkillBonus(bonuses, ctx.skills, "blitz", "闪电战", "+", "速度（持续生效）");
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

    /**
     * 医疗科技加成 - 对应 JS Core.medicalMul()
     * <p>
     * min(0.9, 0.05 * log_medical)
     */
    private double medicalMul(Map<String, Integer> tech) {
        if (tech == null) return 0;
        int lv = tech.getOrDefault("log_medical", 0);
        return Math.min(0.9, 0.05 * lv);
    }

    // ========================================================================
    // 战斗辅助方法
    // ========================================================================

    /** 行动方枚举 */
    private enum Side { MINE, ENEMY }

    /** 科技/技能上下文 */
    private record TechCtx(Map<String, Integer> tech, Map<String, Integer> skills, int commanderMil) {}

    /** 行动序列条目 */
    private record ActionEntry(Side side, String id, int range, double spd) {}

    /**
     * 构建行动序列 - 对应 JS Battle.buildOrder
     * <p>
     * 按射程降序、速度降序排序
     */
    private List<ActionEntry> buildOrder(Map<String, Integer> mine, Map<String, Integer> enemy,
                                         TechCtx mineCtx, TechCtx foeCtx) {
        List<ActionEntry> arr = new ArrayList<>();

        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = mineCtx != null
                    ? effSpd(e.getKey(), mineCtx.tech, mineCtx.skills)
                    : u.spd();
            arr.add(new ActionEntry(Side.MINE, e.getKey(), u.range(), spd));
        }
        for (Map.Entry<String, Integer> e : enemy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = foeCtx != null
                    ? effSpd(e.getKey(), foeCtx.tech, foeCtx.skills)
                    : u.spd();
            arr.add(new ActionEntry(Side.ENEMY, e.getKey(), u.range(), spd));
        }

        // 排序: 射程降序, 速度降序 - 对应 JS arr.sort
        arr.sort((a, b) -> {
            if (b.range != a.range) return b.range - a.range;
            return Double.compare(b.spd, a.spd);
        });
        return arr;
    }

    /**
     * 选择攻击目标 - 对应 JS Battle.pickTarget
     * <p>
     * 1. 如果攻击者有 strongVs 且目标存在: 优先攻击相克目标
     * 2. 否则: 攻击 HP 最低的单位
     */
    private String pickTarget(String attackerId, Map<String, Integer> foeArmy) {
        UnitStats u = getStats(attackerId);
        if (u != null && u.strongVs() != null && foeArmy.getOrDefault(u.strongVs(), 0) > 0) {
            return u.strongVs();
        }
        String best = null;
        int bestHp = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : foeArmy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats fu = getStats(e.getKey());
            if (fu == null) continue;
            if (fu.hp() < bestHp) {
                best = e.getKey();
                bestHp = fu.hp();
            }
        }
        return best;
    }

    /**
     * 相克倍率 - 对应 JS counterMul
     * <p>
     * strongVs 匹配时 1.5, 否则 1.0
     */
    private double counterMul(String attackerId, String defenderId) {
        UnitStats u = getStats(attackerId);
        if (u != null && u.strongVs() != null && u.strongVs().equals(defenderId)) return 1.5;
        return 1.0;
    }

    /**
     * 单位经验价值 - 对应 JS unitExpValue
     * <p>
     * floor((hp + atk*3 + def*2) / 10) + 1
     */
    private int unitExpValue(String unitId) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        return (int) Math.floor((u.hp() + u.atk() * 3 + u.def() * 2) / 10.0) + 1;
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
     * 最低基础速度 (基础 spd，不含科技/技能) - 用于估算初始交战距离。
     * 返回 Integer.MAX_VALUE 表示该 army 没有任何可行动单位。
     */
    private int minSpdOf(Map<String, Integer> army) {
        int ms = Integer.MAX_VALUE;
        for (String id : army.keySet()) {
            if (army.getOrDefault(id, 0) <= 0) continue;
            UnitStats u = getStats(id);
            if (u != null && u.spd() > 0 && u.spd() < ms) ms = u.spd();
        }
        return ms;
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
     * 计算指定单位到敌方当前所有存活单位的最近距离。
     */
    private int minDistanceToLivingFoe(Side side, String myUnitId, Map<String, Integer> foeArmy,
                                       Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        int minDist = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : foeArmy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            int d = getUnitDistToFoe(side, myUnitId, e.getKey(), minePos, enemyPos, initialDist);
            if (d < minDist) minDist = d;
        }
        return minDist == Integer.MAX_VALUE ? 0 : minDist;
    }

    /**
     * 在射程内选择攻击目标:
     * 1. 优先选择相克目标 (strongVs, 且必须在射程内且存活)
     * 2. 否则在射程内的敌方存活单位中选择 HP 最低的单位 (HP 相同选距离最近的)
     * 3. 若射程内无任何敌军，返回 null
     */
    private String pickTargetInRange(String attackerId, Side side, Map<String, Integer> foeArmy,
                                     Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        UnitStats u = getStats(attackerId);
        if (u == null) return null;
        int range = u.range();

        if (u.strongVs() != null && foeArmy.getOrDefault(u.strongVs(), 0) > 0) {
            int d = getUnitDistToFoe(side, attackerId, u.strongVs(), minePos, enemyPos, initialDist);
            if (d <= range) {
                return u.strongVs();
            }
        }

        String best = null;
        int bestHp = Integer.MAX_VALUE;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : foeArmy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            int d = getUnitDistToFoe(side, attackerId, e.getKey(), minePos, enemyPos, initialDist);
            if (d <= range) {
                UnitStats fu = getStats(e.getKey());
                if (fu == null) continue;
                if (fu.hp() < bestHp || (fu.hp() == bestHp && d < bestDist)) {
                    best = e.getKey();
                    bestHp = fu.hp();
                    bestDist = d;
                }
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
            case "assault" -> "远轰";
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
            double def = effDef(unitType, t, s, 0, 0, true, true);
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
