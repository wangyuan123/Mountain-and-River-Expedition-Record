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
 *   kills = min(foeArmy[target], floor(dmg / hpPer))
 *   if (kills < 1 && dmg > 0) kills = 1
 * </pre>
 * 其中:
 * <ul>
 *   <li>atk = effAtk(unitId) - 有效攻击力</li>
 *   <li>count = 攻击方该兵种数量</li>
 *   <li>cm = counterMul - 相克倍率 (strongVs 时 1.5, 否则 1.0)</li>
 *   <li>closeMul = 贴脸倍率 (距离 0 时 2.0, 否则 1.0)</li>
 *   <li>def = effDef(target) - 有效防御力 (被破甲技能削减, 最低 1)</li>
 *   <li>hpPer = effHp(target) - 每单位有效生命值</li>
 * </ul>
 */
@Service
public class BattleService {

    private static final int MAX_ROUND = 30;

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
                    u.strongVs(), u.autoAdvance() != null && u.autoAdvance());
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

        int maxRange = maxRangeOf(myArmy);
        int foeMaxRange = maxRangeOf(foeArmy);
        if (foeMaxRange > maxRange) maxRange = foeMaxRange;
        int dist = maxRange + 2000;

        StringBuilder report = new StringBuilder();

        for (int round = 0; round < MAX_ROUND; round++) {
            List<ActionEntry> order = buildOrder(myArmy, foeArmy, null, null);
            for (ActionEntry a : order) {
                Map<String, Integer> myA = a.side == Side.MINE ? myArmy : foeArmy;
                Map<String, Integer> foe = a.side == Side.MINE ? foeArmy : myArmy;
                if (myA.getOrDefault(a.id, 0) <= 0) continue;
                if (allDead(foe)) break;
                dist = simAct(a.id, myA, foe, dist, report, a.side,
                        null, null, 0, false);
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
     * <p>
     * 完整模拟 30 回合战斗, 每回合:
     * <ol>
     *   <li>判断军官是否生效 (每 3 回合生效一次)</li>
     *   <li>按射程降序、速度降序构建行动序列</li>
     *   <li>每个单位依次行动: 射程外前进, 射程内攻击</li>
     * </ol>
     * 战斗结束后计算掠夺资源、经验、幸存单位。
     *
     * @param attackerArmy            攻方军队
     * @param defenderArmy            守方军队
     * @param defenderForts           守方城防 (fortType -> count)
     * @param attackerTech            攻方科技 (techKey -> level)
     * @param defenderTech            守方科技
     * @param attackerSkills          攻方军官技能 (skillId -> level)
     * @param defenderSkills          守方军官技能
     * @param attackerCommanderMil    攻方指挥官军事值
     * @param defenderCommanderMil    守方指挥官军事值
     * @param attackerWallLevel       攻方围墙等级 (守城时防御加成)
     * @param defenderWallLevel       守方围墙等级
     * @param action                  行动类型: "conquer" 或 "plunder"
     * @param defenderResources       守方资源 (food/steel/oil/rare/gold -> amount)
     * @param defenderWarehouseLevel  守方仓库等级 (资源保护)
     * @return 战斗结果
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

        // 计算战场初始距离
        // 历史 BUG: 这里硬编码了 maxRange + 2000 作为开战距离,
        // 导致侦察兵(高 spd、低 hp) 几乎无法在 30 回合内接敌,被白嫖至死。
        // 修正: 初始距离 = 双方最大射程 + 1 个回合的行军距离(双方中较慢者 * 行军步长)
        // 这样两军都能在第 1 回合发起第一次攻击,符合 "maxRange = 跨射距离" 的设计意图。
        int maxRange = maxRangeOf(mine);
        int foeMaxRange = maxRangeOf(enemy);
        if (foeMaxRange > maxRange) maxRange = foeMaxRange;
        int minSpd = Math.min(minSpdOf(mine), minSpdOf(enemy));
        if (minSpd <= 0 || minSpd == Integer.MAX_VALUE) minSpd = 1;
        // MOVE_PER_ACTION = spd * 50 (见 simAct 实现)
        int initialDist = maxRange + minSpd * 50;
        int dist = initialDist;

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
            report.append(buildCommanderBonusLog("我方", attackerCtx, officerActive, true)).append("\n");
            report.append(buildCommanderBonusLog("敌方", defenderCtx, officerActive, false)).append("\n");

            // 构建行动序列 - 对应 JS buildOrder
            List<ActionEntry> order = buildOrder(mine, enemy, attackerCtx, defenderCtx);

            boolean moved = false;
            for (ActionEntry a : order) {
                Map<String, Integer> myA = a.side == Side.MINE ? mine : enemy;
                Map<String, Integer> foe = a.side == Side.MINE ? enemy : mine;
                if (myA.getOrDefault(a.id, 0) <= 0) continue;
                if (allDead(foe)) break;

                int before = dist;
                // 行动方科技上下文
                TechCtx actCtx = a.side == Side.MINE
                        ? new TechCtx(aTech, aSkills, attackerCommanderMil)
                        : new TechCtx(dTech, dSkills, defenderCommanderMil);
                // 防守方科技上下文 (目标所属方, 与行动方相反)
                TechCtx foeCtx = a.side == Side.MINE
                        ? new TechCtx(dTech, dSkills, defenderCommanderMil)
                        : new TechCtx(aTech, aSkills, attackerCommanderMil);
                int foeWallLevel = a.side == Side.MINE ? defenderWallLevel : attackerWallLevel;

                dist = simAct(a.id, myA, foe, dist, report, a.side,
                        actCtx, foeCtx, foeWallLevel, officerActive);
                if (dist != before) moved = true;
            }

            if (!moved && !anyInRange(mine, enemy, dist)) {
                report.append("双方仍在接近中... 当前距离 ").append(dist).append("\n");
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

        return new BattleResult(win, survivorAttacker, survivorDefender,
                plunderedResources, expGained, report.toString(), cityConquered);
    }

    // ========================================================================
    // simAct - 单个单位行动 (对应 JS Battle.simAct)
    // ========================================================================

    /**
     * 模拟一个单位的行动: 移动或攻击。
     *
     * @param unitId        行动单位 ID
     * @param myArmy        行动方军队 (会被修改)
     * @param foeArmy       敌方军队 (会被修改)
     * @param dist          当前战场距离
     * @param report        战斗报告
     * @param side          哪一方
     * @param actCtx        行动方科技/技能上下文
     * @param foeCtx        防守方科技/技能上下文
     * @param foeWallLevel  防守方围墙等级
     * @param officerActive 军官是否生效
     * @return 新的战场距离
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
        UnitStats u = getStats(unitId);
        if (u == null) return dist;

        // 速度计算 - 对应 JS simAct 中 spd 的取值
        double spd;
        if (actCtx != null) {
            spd = effSpd(unitId, actCtx.tech, actCtx.skills);
        } else {
            spd = u.spd(); // resolveWild 场景: 基础速度
        }

        // 射程外: 移动阶段 - 对应 JS simAct 中 u.range < dist 分支
        if (u.range() < dist) {
            int step = Math.min((int) (spd * 50), dist);
            int newDist = dist - step;
            String sidePrefix = side == Side.MINE ? "我方" : "敌方";
            report.append(sidePrefix).append(u.name())
                    .append("(").append(myArmy.getOrDefault(unitId, 0)).append(")")
                    .append(" 前进 ").append(step).append(" 距离->").append(newDist).append("\n");
            return newDist;
        }

        // 射程内: 攻击阶段
        String sidePrefix = side == Side.MINE ? "我方" : "敌方";
        report.append(sidePrefix).append(u.name())
                .append("(").append(myArmy.getOrDefault(unitId, 0)).append(")")
                .append(" 前进0(射程内) 距离").append(dist).append("\n");

        // 选择目标 - 对应 JS pickTarget
        String target = pickTarget(unitId, foeArmy);
        if (target == null) return dist;

        int count = myArmy.getOrDefault(unitId, 0);
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

        // 贴脸倍率 - 对应 JS closeMul (距离 0 时 ×2)
        double closeMul = dist == 0 ? 2 : 1;

        // 伤害公式 - 对应 JS dmg = (atk * atk * count * cm * closeMul) / (def * 10)
        double dmg = (atk * atk * count * cm * closeMul) / (def * 10);

        // 连击技能 - 对应 JS combo (仅攻方 mine 侧生效)
        double comboRate = 0;
        if (actCtx != null && side == Side.MINE) {
            comboRate = skillBonus(actCtx.skills, "combo");
        }
        boolean comboHit = comboRate > 0 && ThreadLocalRandom.current().nextDouble() < comboRate;
        if (comboHit) dmg *= 2;

        // 有效生命值 - 对应 JS effHp (始终生效, 不依赖 officerActive)
        double hpPer;
        if (foeCtx != null) {
            hpPer = effHp(target, foeCtx.tech);
        } else {
            UnitStats tStats = getStats(target);
            hpPer = tStats != null ? tStats.hp() : 1;
        }

        // 击杀数 - 对应 JS kills = min(foeArmy[target], floor(dmg / hpPer))
        int kills = Math.min(foeArmy.getOrDefault(target, 0), (int) Math.floor(dmg / hpPer));
        // 最少击杀 1 (只要有伤害) - 对应 JS if (kills < 1 && dmg > 0) kills = 1
        if (kills < 1 && dmg > 0) kills = 1;

        // 应用击杀
        int beforeKill = foeArmy.getOrDefault(target, 0);
        foeArmy.put(target, Math.max(0, beforeKill - kills));

        // 记录战斗日志
        UnitStats tU = getStats(target);
        String targetName = side == Side.MINE ? "敌" : "我";
        StringBuilder bonusTag = new StringBuilder();
        if (side == Side.MINE && baseAtk > 0 && atk / baseAtk > 1.01) {
            bonusTag.append(" 军官加成×").append(String.format("%.2f", atk / baseAtk));
        }
        if (pierce > 0) bonusTag.append(" 破甲").append((int) Math.round(pierce * 100)).append("%");
        if (comboHit) bonusTag.append(" 连击");
        if (cm > 1) bonusTag.append(" 相克");
        if (closeMul > 1) bonusTag.append(" 贴脸");

        report.append(sidePrefix).append(u.name()).append("(").append(count).append(")")
                .append(verb(unitId))
                .append(targetName).append(tU != null ? tU.name() : target)
                .append("(").append(beforeKill).append(")")
                .append(bonusTag.length() > 0 ? " [" + bonusTag.toString().trim() + "]" : "")
                .append(" 伤害").append(dmg >= 10000 ? Math.round(dmg) : String.format("%.0f", dmg))
                .append(" 击毁").append(kills).append("\n");

        return dist;
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
     * (1 + 0.05*cmd_attack) * (1 + 0.05*cat_attack) * (1 + cmdMil/100)
     */
    private double atkMul(String cat, Map<String, Integer> tech, int commanderMil) {
        int cmdAttack = tech.getOrDefault("cmd_attack", 0);
        String catKey = catAtkKey(cat);
        int catAttack = catKey != null ? tech.getOrDefault(catKey, 0) : 0;
        double allMul = 1 + 0.05 * cmdAttack;
        double catMul = 1 + 0.05 * catAttack;
        return allMul * catMul * (1 + commanderMil / 100.0);
    }

    /**
     * 防御倍率 - 对应 JS Core.defMul(cat, isMine)
     * <p>
     * (1 + 0.05*cmd_defense) * (1 + 0.05*cat_defense) * wallMul
     * wallMul = (isMine && cat != 'air') ? (1 + 0.05*wall) : 1
     */
    private double defMul(String cat, Map<String, Integer> tech, int wallLevel, boolean isMine) {
        int cmdDefense = tech.getOrDefault("cmd_defense", 0);
        String catKey = catDefKey(cat);
        int catDefense = catKey != null ? tech.getOrDefault(catKey, 0) : 0;
        double allMul = 1 + 0.05 * cmdDefense;
        double catMul = 1 + 0.05 * catDefense;
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
    private String catAtkKey(String cat) {
        return switch (cat) {
            case "inf" -> "inf_attack";
            case "arm" -> "arm_attack";
            case "air" -> "air_attack";
            case "nav" -> "nav_attack";
            default -> null;
        };
    }

    /** cat -> 防御科技 key */
    private String catDefKey(String cat) {
        return switch (cat) {
            case "inf" -> "inf_defense";
            case "arm" -> "arm_defense";
            case "air" -> "air_defense";
            case "nav" -> "nav_defense";
            default -> null;
        };
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

        StringBuilder message = new StringBuilder("军事属性及攻防技能本回合未生效");
        double blitz = skillBonus(ctx.skills, "blitz");
        if (blitz > 0) {
            message.append("；闪电战 +").append(percent(blitz)).append("%速度（持续生效）");
        }
        return sideName + "将领加成：" + message;
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

        return new BattleResult(win, survivorAttacker, survivorDefender,
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
