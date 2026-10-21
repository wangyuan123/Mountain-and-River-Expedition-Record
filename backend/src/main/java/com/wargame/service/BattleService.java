package com.wargame.service;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.BattleScene;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.JapaneseUnitDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.ConquestWallState;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务端战斗结算：针对目标领域的线性攻击 × 专项克制 × 100/(100 + 5×有效防御)。
 * 每次行动共享一份攻击额度；跨地空海及工事切换目标时按剩余额度使用对应武器，不能重新打一轮。
 * 重坦前置且掩护身后的地面单位，特种兵可绕过掩护；空海目标不受地面掩护影响。
 * 所有兵种均被敌方存活部队阻挡，必须消灭前排后才能继续向后推进。
 */
@Service
public class BattleService {

    /** 独立演习和 resolveWild 基础模拟使用 24 回合；正式出征按战斗类型选择上限。 */
    public static final int MAX_ROUND = 24;
    public static final int PLUNDER_MAX_ROUND = 25;
    public static final int CONQUER_MAX_ROUND = 35;

    /** 世界战斗按出征类型收束；兼容旧 tactical_ 标识，缺类型时沿用默认征服语义。 */
    public static int maxRoundForAction(String action) {
        String normalized = action == null ? "conquer" : action.replaceFirst("^tactical_", "");
        return switch (normalized) {
            case "plunder" -> PLUNDER_MAX_ROUND;
            case "feint" -> 10;
            case "harass" -> 15;
            case "guerrilla" -> 5;
            default -> CONQUER_MAX_ROUND;
        };
    }

    /** 短回合袭击只造成战斗损失，不能取得资源、钻石或目标归属。 */
    public static boolean isHarassmentAction(String action) {
        return "feint".equals(action) || "harass".equals(action) || "guerrilla".equals(action);
    }

    /** 攻击资格、排队和战斗状态必须覆盖全部五种出征动作。 */
    public static boolean isOffensiveAction(String action) {
        return "conquer".equals(action) || "plunder".equals(action) || isHarassmentAction(action);
    }

    public static String actionName(String action) {
        if (action == null) return "征服";
        return switch (action) {
            case "plunder" -> "掠夺";
            case "feint" -> "佯攻";
            case "harass" -> "袭扰";
            case "guerrilla" -> "游击";
            default -> "征服";
        };
    }
    /** 与当前目标距离为 0 时，步兵冲锋火力翻倍，轻重坦和特种兵获得 1.5 倍贴脸伤害。 */
    private static final Map<String, Double> POINT_BLANK_MULTIPLIERS = Map.of(
            "infantry", 2.0, "ltank", 1.5, "htank", 1.5, "special", 1.5);
    private final java.util.function.DoubleSupplier random;
    public enum TerminalStrategy { WORLD, PVP_PRACTICE }
    private List<Map<String, Object>> practiceEvents;
    private boolean tracePractice = true;

    /** 结构化事件只在独立演习引擎实例收集，不解析中文战报，不共享随机流。 */
    public List<Map<String, Object>> practiceEvents() {
        return practiceEvents == null ? List.of() : List.copyOf(practiceEvents);
    }
    private void event(String type, Side side, String unit, String target, Map<String, ?> effects) {
        if (practiceEvents == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type); e.put("side", side == Side.MINE ? "A" : "B");
        e.put("unit", unit); e.put("target", target); e.putAll(effects); practiceEvents.add(e);
    }

    /** 匹配估值的输出包含行动火力，将领周期属性按三回合均值计算；火力倍率单列以区别武器攻击。 */
    public Map<String, Double> practiceStats(String id, TechCtx ctx) {
        UnitStats u = getStats(id);
        double firepower = actionFirepowerMultiplier(ctx.tech);
        double atk = atkMul(u.cat(), ctx.tech, 0) * (1 + ctx.commanderMil / 300.0) * ctx.commandBonus.attackMul() * firepower;
        return Map.of("ground", u.atkGround() * atk * (1 + Math.min(.15, UnitEquipmentEffects.percent(ctx.tech, id, "ground"))),
                "air", u.atkAir() * atk * (1 + Math.min(.15, UnitEquipmentEffects.percent(ctx.tech, id, "air"))),
                "sea", u.atkSea() * atk * (1 + Math.min(.15, UnitEquipmentEffects.percent(ctx.tech, id, "sea"))),
                "defense", effDef(id, ctx.tech, Map.of(), 0, false, false) * (1 + ctx.commanderDef / 300.0) * ctx.commandBonus.defenseMul(),
                "hp", effHp(id, ctx.tech) * ctx.commandBonus.hpMul(), "speed", effSpd(id, ctx), "range", (double) effectiveRange(id, ctx),
                "firepowerMultiplier", firepower);
    }

    /** 中立演习入口：终局只负责停止，由演习服务对称判胜/平。 */
    public BattleRoundState resolvePracticeRound(Map<String, Integer> a, Map<String, Integer> b,
            Map<String, Integer> ap, Map<String, Integer> bp, int distance, TechCtx ac, TechCtx bc,
            int round, int firstCombat, Map<String, UnitOrder> ao, Map<String, UnitOrder> bo) {
        practiceEvents = tracePractice ? new ArrayList<>() : null;
        return resolveRound(a, b, ap, bp, distance, ac.tech, bc.tech, ac.skills, bc.skills,
                ac.commanderMil, ac.commanderDef, bc.commanderMil, bc.commanderDef,
                round, firstCombat, ao, bo, ac.commandBonus, bc.commandBonus, TerminalStrategy.PVP_PRACTICE, MAX_ROUND);
    }

    public BattleService() {
        random = () -> ThreadLocalRandom.current().nextDouble();
    }

    /** 固定种子用于复现平衡实验；默认服务仍按每次战斗的随机抽样结算。 */
    public BattleService(long seed) {
        random = new Random(seed)::nextDouble;
    }

    /** 校准只运行数值内核，不收集无需保存的事件。 */
    public BattleService(long seed, boolean trace) { this(seed); tracePractice = trace; }

    /** 军官技能每级加成比率 - 对应 JS Core.skillBonus 中的 rates */
    private static final Map<String, Double> SKILL_RATES = Map.ofEntries(
            Map.entry("frenzy", 0.10),
            Map.entry("bulwark", 0.10),
            Map.entry("blitz", 0.06),
            Map.entry("suppress", 0.06),
            Map.entry("pierce", 0.06),
            Map.entry("learn", 0.06),
            Map.entry("borrow_armor", 0.06),
            Map.entry("leadership", 0.04),
            Map.entry("supply", 0.04),
            Map.entry("medic", 0.03),
            Map.entry("counter", 0.20),
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
        // 仅用于墙体机动几何；耐久在独立状态中结算，绝不塞入 defenderArmy。
        if (ConquestWallState.TARGET.equals(id)) {
            return new UnitStats(id, "城墙", "fort", 0, 0, 0, 0, 0, 0, 0, 0, "", false);
        }
        UnitDef u = GameData.UNITS.get(UnitEquipmentEffects.base(id));
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
     * 获取战报日志中显示的兵种名称。
     * 当守方为日寇/NPC且该单位属于防守方（Side.ENEMY）时，显示日军专属名称；否则显示盟军名称。
     */
    private String unitName(String unitId, Side side, boolean defenderIsJapanese) {
        boolean isJapanese = (side == Side.ENEMY && defenderIsJapanese);
        return JapaneseUnitDef.getUnitName(UnitEquipmentEffects.base(unitId), isJapanese) + (unitId.contains("~") ? "（援军配装组）" : "");
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

    /** 兼容未指定场景的玩家战斗和演习调用；新世界入口必须显式传入场景。 */
    public int calcInitialDistance(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
                                  TechCtx attackerCtx, TechCtx defenderCtx) {
        return calcInitialDistance(attackerArmy, defenderArmy, attackerCtx, defenderCtx, null, BattleScene.PLAYER);
    }

    /** 空城墙体射程为0；保留旧征服入口，按玩家战斗距离计算。 */
    public int calcInitialDistance(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
                                  TechCtx attackerCtx, TechCtx defenderCtx, ConquestWallState wall) {
        return calcInitialDistance(attackerArmy, defenderArmy, attackerCtx, defenderCtx, wall, BattleScene.PLAYER);
    }

    /**
     * 开局距离为双方存活兵种、工事的最大有效射程加场景缓冲，不叠加双方射程或移速。
     * 玩家战斗/演习+1850、NPC+1000、野地+500；空城也计入攻方射程，墙体不增加距离。
     * 仅创建战场时调用，进行中会话继续使用已保存距离；不再保证前三回合安全。
     */
    public int calcInitialDistance(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
                                  TechCtx attackerCtx, TechCtx defenderCtx, ConquestWallState wall, BattleScene scene) {
        return Math.max(maxEffectiveRange(attackerArmy, attackerCtx), maxEffectiveRange(defenderArmy, defenderCtx))
                + Objects.requireNonNull(scene, "战斗场景不能为空").buffer();
    }

    /** 仅能攻击实际敌军的正数兵种决定伏击距离，后勤与无有效攻击领域的单位不能拉长火力区。 */
    public int ambushDistance(Map<String, Integer> interceptors, Map<String, Integer> incoming, TechCtx ctx) {
        int range = 0;
        for (var entry : interceptors.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || getStats(entry.getKey()) == null) continue;
            boolean canFire = incoming.entrySet().stream().anyMatch(target -> target.getValue() != null
                    && target.getValue() > 0 && getStats(target.getKey()) != null && baseAttack(entry.getKey(), target.getKey()) > 0);
            if (canFire) range = Math.max(range, effectiveRange(entry.getKey(), ctx));
        }
        return range;
    }

    /** 仅实际参战的正数兵力参与射程上限，援军配置组沿用基础兵种射程。 */
    private int maxEffectiveRange(Map<String, Integer> army, TechCtx ctx) {
        if (army == null) return 0;
        int range = 0;
        for (var entry : army.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0 && getStats(entry.getKey()) != null) {
                range = Math.max(range, effectiveRange(entry.getKey(), ctx));
            }
        }
        return range;
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

        int initialDist = calcInitialDistance(myArmy, foeArmy, null, null, null, BattleScene.WILD);
        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : myArmy.keySet()) minePos.put(k, formationOffset(k));
        for (String k : foeArmy.keySet()) enemyPos.put(k, initialDist - formationOffset(k));

        StringBuilder report = new StringBuilder("规则版本：" + BattleRules.VERSION + "\n");
        report.append("战场初始距离: ").append(initialDist).append("\n");

        for (int round = 1; round <= MAX_ROUND; round++) {
            report.append("-- 第").append(round).append("回合 --\n");

            // 阶段一: 统一机动
            int movementReportStart = report.length();
            executeMovementPhase(myArmy, foeArmy, minePos, enemyPos, initialDist, report,
                    attackerOrders, defenderOrders, null, null);

            // 阶段二: 战术交火
            executeCombatPhase(myArmy, foeArmy, minePos, enemyPos, initialDist, report,
                    movementReportStart,
                    attackerOrders, defenderOrders, null, null, false, round, 0);

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
    // startWorldDispatch - 世界出征战斗 (含科技、军官与参战工事)
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
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, 0,
                defenderCommanderMil, 0,
                action, defenderResources, defenderWarehouseLevel, true,
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
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, 0,
                defenderCommanderMil, 0,
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
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts,
                attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef,
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
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders) {
        return startWorldDispatch(
                attackerArmy, defenderArmy, defenderForts, attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef, defenderCommanderMil, defenderCommanderDef, action, defenderResources, defenderWarehouseLevel, isPlayerBattle, attackerOrders, defenderOrders,
                CommandBonus.NONE, CommandBonus.NONE);
    }

    /** 司令官加成常驻，与带兵将领的属性回合、技能周期独立组合。 */
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
            String action,
            Map<String, Integer> defenderResources,
            long defenderWarehouseLevel,
            boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus) {

        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts, attackerTech, defenderTech,
                attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, action, defenderResources, defenderWarehouseLevel,
                isPlayerBattle, attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus, null);
    }

    /** 新玩家城征服入口显式传入开战墙体快照；其他目标与旧调用继续无墙结算。 */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy, Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            String action, Map<String, Integer> defenderResources, long defenderWarehouseLevel, boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, ConquestWallState wall) {
        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts, attackerTech, defenderTech,
                attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, action, defenderResources, defenderWarehouseLevel,
                isPlayerBattle, attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus, wall,
                isPlayerBattle ? BattleScene.PLAYER : BattleScene.NPC);
    }

    /** 世界战斗显式区分目标场景；历史布尔值仍仅管理名称与墙体适用性。 */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy, Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            String action, Map<String, Integer> defenderResources, long defenderWarehouseLevel, boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, ConquestWallState wall, BattleScene scene) {

        return startWorldDispatch(attackerArmy, defenderArmy, defenderForts, attackerTech, defenderTech,
                attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef, defenderCommanderMil,
                defenderCommanderDef, action, defenderResources, defenderWarehouseLevel, isPlayerBattle,
                attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus, wall, scene, false);
    }

    /** 伏击开局显式传入；不交换攻守身份，也不影响其他场景和进行中的战斗会话。 */
    public BattleResult startWorldDispatch(
            Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy, Map<String, Integer> defenderForts,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            String action, Map<String, Integer> defenderResources, long defenderWarehouseLevel, boolean isPlayerBattle,
            Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, ConquestWallState wall,
            BattleScene scene, boolean defenderAmbush) {
        if (!isPlayerBattle || !"conquer".equals(action) && !"tactical_conquer".equals(action)) wall = null;

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

        TechCtx attackerCtx = new TechCtx(aTech, aSkills, attackerCommanderMil, attackerCommanderDef, attackerCommandBonus);
        TechCtx defenderCtx = new TechCtx(dTech, dSkills, defenderCommanderMil, defenderCommanderDef, defenderCommandBonus);

        // 保存初始军队快照 (用于战后经验计算) - 对应 JS mineStart / enemyStart
        Map<String, Integer> mineStart = snapshot(mine);
        Map<String, Integer> enemyStart = snapshot(enemy);

        // 动态计算初始战场距离
        int ambushDistance = defenderAmbush && "intercept_scout".equals(action)
                ? ambushDistance(enemy, mine, defenderCtx) : 0;
        boolean ambush = ambushDistance > 0;
        int initialDist = ambush ? ambushDistance : calcInitialDistance(mine, enemy, attackerCtx, defenderCtx, wall, scene);

        Map<String, Integer> minePos = new LinkedHashMap<>();
        Map<String, Integer> enemyPos = new LinkedHashMap<>();
        for (String k : mine.keySet()) minePos.put(k, formationOffset(k));
        for (String k : enemy.keySet()) enemyPos.put(k, initialDist - formationOffset(k));

        StringBuilder report = new StringBuilder("规则版本：" + BattleRules.VERSION + "\n");
        report.append("战场初始距离: ").append(initialDist).append("\n");

        // 首次交战回合由实际可开火目标确定，双方技能共用同一个周期起点。
        int firstCombatRound = 0;
        // 自动结算和逐回合指挥共用同一类型上限，最后一回合先交火再判定撤退。
        int maxRound = maxRoundForAction(action);
        for (int round = 1; round <= maxRound; round++) {
            // 同步、手动及超时均调用同一回合内核，清军后仅共享同一份攻击额度溢出到墙体。
            BattleRoundState state = resolveRound(mine, enemy, minePos, enemyPos, initialDist,
                    aTech, dTech, aSkills, dSkills, attackerCommanderMil, attackerCommanderDef,
                    defenderCommanderMil, defenderCommanderDef, round, firstCombatRound,
                    attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus,
                    TerminalStrategy.WORLD, maxRound, wall, !isPlayerBattle, ambush && round == 1);
            mine = state.attackerArmy();
            enemy = state.defenderArmy();
            minePos = state.attackerPositions();
            enemyPos = state.defenderPositions();
            firstCombatRound = state.firstCombatRound();
            wall = state.wall();
            report.append(state.log());
            if (state.finished()) {
                BattleResult result = finishBattle(state.attackerWin(), mine, enemy, mineStart, enemyStart,
                        aTech, dTech, action, defenderResources, defenderWarehouseLevel, report);
                result.setWall(wall);
                result.setTerminalReason(state.terminalReason());
                if ("intercept_scout".equals(action)) result.setInterceptionOpening(Map.of("version", "interception-ambush-v1-20261021",
                        "mode", ambush ? "ambush" : "urgent", "firstStrikeSide", ambush ? "defender" : "standard",
                        "firstCombatRound", firstCombatRound, "distance", initialDist,
                        "ambushConsumed", ambush && firstCombatRound == 1));
                return result;
            }
        }

        // 安全兜底
        return finishBattle(false, mine, enemy, mineStart, enemyStart,
                aTech, dTech, action, defenderResources, defenderWarehouseLevel, report);
    }

    /**
     * 无历史交战起点的单回合入口。连续结算须使用带 firstCombatRound 的重载，
     * 并持久化返回的位置、存活单位和首次交战回合，确保刷新页面不会重置战场或技能周期。
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
                                              int round,
                                              Map<String, UnitOrder> attackerOrders,
                                              Map<String, UnitOrder> defenderOrders) {
        return resolveWorldRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions,
                initialDistance, attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef, defenderCommanderMil, defenderCommanderDef, round, 0, attackerOrders, defenderOrders);
    }

    /**
     * 结算一回合并延续首次交战起算的技能周期。调用方须持久化 firstCombatRound，
     * 下一回合原样传回；0 表示尚未发生交火，交战后即使脱离射程也不重置周期。
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
                                              int round,
                                              int firstCombatRound,
                                              Map<String, UnitOrder> attackerOrders,
                                              Map<String, UnitOrder> defenderOrders) {
        return resolveWorldRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions,
                initialDistance, attackerTech, defenderTech, attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef, defenderCommanderMil, defenderCommanderDef, round, firstCombatRound, attackerOrders, defenderOrders,
                CommandBonus.NONE, CommandBonus.NONE);
    }

    /** 兼容未传战斗类型的单回合调用，默认按征服结算；正式会话使用带 action 的重载。 */
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
                                              int round,
                                              int firstCombatRound,
                                              Map<String, UnitOrder> attackerOrders,
                                              Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus) {
        return resolveWorldRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, round, firstCombatRound,
                attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus, "conquer");
    }

    /** 逐回合结算使用会话保存的出征类型，双方视角、手动和超时推进共用同一终局边界。 */
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
                                              int round,
                                              int firstCombatRound,
                                              Map<String, UnitOrder> attackerOrders,
                                              Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, String action) {
        return resolveRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, round, firstCombatRound,
                attackerOrders, defenderOrders, attackerCommandBonus, defenderCommandBonus, TerminalStrategy.WORLD,
                maxRoundForAction(action));
    }

    private BattleRoundState resolveRound(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
            Map<String, Integer> attackerPositions, Map<String, Integer> defenderPositions, int initialDistance,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef, int round, int firstCombatRound,
            Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, TerminalStrategy strategy, int maxRound) {
        return resolveRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, round, firstCombatRound, attackerOrders, defenderOrders,
                attackerCommandBonus, defenderCommandBonus, strategy, maxRound, null, false);
    }

    /** 墙体原样延续，旧会话传 null；由调用方冻结适用范围，不读取实时建筑或守方成长。 */
    public BattleRoundState resolveWorldRound(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
            Map<String, Integer> attackerPositions, Map<String, Integer> defenderPositions, int initialDistance,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            int round, int firstCombatRound, Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, String action, ConquestWallState wall) {
        if (!"conquer".equals(action) && !"tactical_conquer".equals(action)) wall = null;
        return resolveRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, round, firstCombatRound, attackerOrders, defenderOrders,
                attackerCommandBonus, defenderCommandBonus, TerminalStrategy.WORLD, maxRoundForAction(action), wall, false);
    }

    private BattleRoundState resolveRound(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
            Map<String, Integer> attackerPositions, Map<String, Integer> defenderPositions, int initialDistance,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            int round, int firstCombatRound, Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, TerminalStrategy strategy, int maxRound,
            ConquestWallState wall, boolean defenderIsJapanese) {
        return resolveRound(attackerArmy, defenderArmy, attackerPositions, defenderPositions, initialDistance,
                attackerTech, defenderTech, attackerSkills, defenderSkills, attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef, round, firstCombatRound, attackerOrders, defenderOrders,
                attackerCommandBonus, defenderCommandBonus, strategy, maxRound, wall, defenderIsJapanese, false);
    }

    /** 伏击仅首回合跳过机动，随后共享同一攻击内核，每个兵种仍只消耗一份攻击额度。 */
    private BattleRoundState resolveRound(Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy,
            Map<String, Integer> attackerPositions, Map<String, Integer> defenderPositions, int initialDistance,
            Map<String, Integer> attackerTech, Map<String, Integer> defenderTech,
            Map<String, Integer> attackerSkills, Map<String, Integer> defenderSkills,
            int attackerCommanderMil, int attackerCommanderDef, int defenderCommanderMil, int defenderCommanderDef,
            int round, int firstCombatRound, Map<String, UnitOrder> attackerOrders, Map<String, UnitOrder> defenderOrders,
            CommandBonus attackerCommandBonus, CommandBonus defenderCommandBonus, TerminalStrategy strategy, int maxRound,
            ConquestWallState wall, boolean defenderIsJapanese, boolean ambushFirstRound) {
        Map<String, Integer> mine = snapshot(attackerArmy);
        Map<String, Integer> enemy = snapshot(defenderArmy);
        Map<String, Integer> minePos = new LinkedHashMap<>(attackerPositions);
        Map<String, Integer> enemyPos = new LinkedHashMap<>(defenderPositions);
        normalizeBattleFronts(mine, enemy, minePos, enemyPos, initialDistance);
        TechCtx attackerCtx = new TechCtx(attackerTech != null ? attackerTech : Collections.emptyMap(),
                attackerSkills != null ? attackerSkills : Collections.emptyMap(), attackerCommanderMil, attackerCommanderDef, attackerCommandBonus);
        TechCtx defenderCtx = new TechCtx(defenderTech != null ? defenderTech : Collections.emptyMap(),
                defenderSkills != null ? defenderSkills : Collections.emptyMap(), defenderCommanderMil, defenderCommanderDef, defenderCommandBonus);
        boolean officerActive = isSkillRoundActive(round, firstCombatRound);
        StringBuilder report = new StringBuilder("-- 第").append(round).append("回合 --\n");

        // 回合开始有守军时先正常机动与交火；清军后仅剩余攻击额度可继续攻墙。
        boolean wallPhase = wall != null && wall.standing() && allDead(enemy);
        int movementReportStart = report.length();
        if (wallPhase) {
            report.append("阶段：攻击城墙\n");
            movementReportStart = report.length();
            Map<String, Integer> wallTarget = Map.of(ConquestWallState.TARGET, 1);
            Map<String, Integer> wallPos = Map.of(ConquestWallState.TARGET, initialDistance);
            Map<String, Integer> oldMinePos = new LinkedHashMap<>(minePos);
            moveArmy(Side.MINE, mine, wallTarget, minePos, oldMinePos, wallPos, initialDistance,
                    attackerOrders, Map.of(), attackerCtx, null, isSkillRoundActive(round, firstCombatRound));
            appendMovementReport(Side.MINE, mine, wallTarget, minePos, wallPos, oldMinePos,
                    initialDistance, report, attackerOrders, false);
        } else if (ambushFirstRound) {
            report.append("伏击发动：拦截部队提前部署，射程内拦截部队优先行动；敌军以幸存兵力应战，次回合恢复常规顺序。\n");
            movementReportStart = report.length();
        } else {
            if (wall != null) report.append("阶段：清除守军\n");
            movementReportStart = report.length();
            executeMovementPhase(mine, enemy, minePos, enemyPos, initialDistance, report,
                    attackerOrders, defenderOrders, attackerCtx, defenderCtx, defenderIsJapanese,
                    isSkillRoundActive(round, firstCombatRound));
        }
        if (strategy == TerminalStrategy.PVP_PRACTICE) {
            // 首次交战E在机动后确定，速度加成不能追溯改变已经执行的坐标。
            boolean movementSkill = isSkillRoundActive(round, firstCombatRound);
            minePos.forEach((id, x) -> event("MOVEMENT", Side.MINE, id, "", Map.of("from", attackerPositions.getOrDefault(id, 0), "to", x,
                    "speed", effSpd(id, attackerCtx, movementSkill), "blitzMultiplier", 1 + (movementSkill ? skillBonus(attackerCtx.skills, "blitz") : 0))));
            enemyPos.forEach((id, x) -> event("MOVEMENT", Side.ENEMY, id, "", Map.of("from", defenderPositions.getOrDefault(id, initialDistance), "to", x,
                    "speed", effSpd(id, defenderCtx, movementSkill), "blitzMultiplier", 1 + (movementSkill ? skillBonus(defenderCtx.skills, "blitz") : 0))));
        }
        if (wallPhase) {
            for (String id : mine.keySet()) {
                if (mine.get(id) > 0 && baseAttack(id, ConquestWallState.TARGET) > 0
                        && Math.abs(initialDistance - minePos.getOrDefault(id, 0)) <= effectiveRange(id, attackerCtx)) {
                    if (firstCombatRound == 0) firstCombatRound = round;
                    break;
                }
            }
            wall = attackWall(mine, minePos, initialDistance, attackerCtx, round, firstCombatRound,
                    wall, report, movementReportStart);
        } else {
            WallCombatState wallCombat = wall != null && wall.standing() ? new WallCombatState(wall) : null;
            firstCombatRound = executeCombatPhase(mine, enemy, minePos, enemyPos, initialDistance, report,
                    movementReportStart, attackerOrders, defenderOrders, attackerCtx, defenderCtx,
                    officerActive, round, firstCombatRound, defenderIsJapanese, wallCombat, ambushFirstRound);
            if (wallCombat != null) wall = wallCombat.wall;
        }

        if (strategy == TerminalStrategy.PVP_PRACTICE) {
            return new BattleRoundState(round, allDead(mine) || allDead(enemy) || round >= maxRound, false,
                    filterPositive(mine), filterPositive(enemy), minePos, enemyPos, report.toString(), firstCombatRound);
        }
        if (wall != null) wall = wall.afterRound(!allDead(enemy));
        boolean attackerWin = allDead(enemy) && !allDead(mine) && (wall == null || !wall.standing());
        boolean defenderWin = allDead(mine) || (!attackerWin && round >= maxRound);
        String terminalReason = null;
        if (attackerWin) {
            terminalReason = wall != null && wall.level() > 0 ? "WALL_BREACHED" : "DEFENDERS_ELIMINATED";
            report.append(wall != null && wall.level() > 0 ? "★ 守军已清除，城墙已攻破，胜利!\n" : "★ 全歼敌军,胜利!\n");
        }
        else if (defenderWin && allDead(mine)) report.append("✗ 部队溃败,失败!\n");
        else if (defenderWin) {
            terminalReason = wall != null && wall.standing() && allDead(enemy) ? "WALL_NOT_BREACHED" : "ROUND_LIMIT";
            report.append("WALL_NOT_BREACHED".equals(terminalReason)
                    ? "✗ 守军已清除，但未在期限内破墙，被迫撤退\n" : "✗ 回合耗尽,被迫撤退\n");
        }
        if (defenderWin && allDead(mine)) terminalReason = "ATTACKERS_ELIMINATED";
        if (!attackerWin && !defenderWin && wall != null && wall.standing() && allDead(enemy) && !wallPhase) {
            report.append("守军及工事已清除，继续攻击城墙\n");
        }

        return new BattleRoundState(round, attackerWin || defenderWin, attackerWin,
                filterPositive(mine), filterPositive(enemy), minePos, enemyPos, report.toString(), firstCombatRound,
                wall, terminalReason);
    }

    /** 独立结构扣血：沿用攻方攻坚、配装、贴脸与周期技能，墙体不享受守方加成或反击。 */
    private ConquestWallState attackWall(Map<String, Integer> mine, Map<String, Integer> positions,
            int wallPosition, TechCtx ctx, int round, int firstCombatRound, ConquestWallState wall,
            StringBuilder report, int movementReportStart) {
        boolean skillsActive = isSkillRoundActive(round, firstCombatRound);
        String bonusLog = buildCommanderBonusLog("我方", ctx, round, skillsActive);
        if (!bonusLog.isEmpty()) {
            report.insert(movementReportStart, bonusLog + "\n");
            movementReportStart += bonusLog.length() + 1;
        }
        List<ActionEntry> order = buildOrder(mine, Map.of(), ctx, null, round, skillsActive);
        for (ActionEntry entry : order) {
            if (!wall.standing()) break;
            wall = attackWallUnit(entry.id, mine.get(entry.id), mine.get(entry.id), positions, wallPosition,
                    ctx, skillsActive, skillsActive, wall, report, movementReportStart, false);
        }
        reorderMovementLogsByCombatOrder(report, movementReportStart, order, false);
        return wall;
    }

    /** 回合局部墙体持有者；不存服务字段，避免不同城市并发串用耐久。 */
    private static final class WallCombatState {
        private ConquestWallState wall;
        private WallCombatState(ConquestWallState wall) { this.wall = wall; }
    }

    /** 军事目标和墙体共享行动预算；余伤重新计算攻坚领域、距离及贴脸倍率。 */
    private ConquestWallState attackWallUnit(String unitId, int count, double remainingActions,
            Map<String, Integer> positions, int wallPosition, TechCtx ctx, boolean officerActive,
            boolean skillsActive, ConquestWallState wall, StringBuilder report, int movementReportStart,
            boolean overflow) {
        int distance = Math.abs(wallPosition - positions.getOrDefault(unitId, 0));
        double fortAttack = baseAttack(unitId, ConquestWallState.TARGET);
        if (!wall.standing() || remainingActions <= 0 || fortAttack <= 0 || distance > effectiveRange(unitId, ctx)) return wall;
        double attack = fortAttack * attackBonus(getStats(unitId).cat(), ctx.tech, ctx.skills,
                ctx.commanderMil, officerActive, skillsActive) * ctx.commandBonus.attackMul()
                * UnitEquipmentEffects.attack(ctx.tech, unitId, ConquestWallState.TARGET);
        double defense = wall.defense() * (1 - (skillsActive ? skillBonus(ctx.skills, "pierce") : 0));
        double contact = distance == 0 ? POINT_BLANK_MULTIPLIERS.getOrDefault(UnitEquipmentEffects.base(unitId), 1.0) : 1.0;
        double damage = attack * remainingActions * actionFirepowerMultiplier(ctx.tech) * contact
                * 100 / (100 + 5 * Math.max(0, defense));
        var before = wall.hpRemaining();
        wall = wall.damage(damage);
        String outcome = (overflow ? "余伤攻击敌城墙" : "攻击敌城墙")
                + "，伤害" + before.subtract(wall.hpRemaining()).setScale(0, java.math.RoundingMode.DOWN)
                + "，墙体剩余生命" + wall.hpRemaining().setScale(0, java.math.RoundingMode.CEILING)
                + "，有效射程" + effectiveRange(unitId, ctx) + " 目标距离" + distance;
        if (!appendCombatOutcomeToMovementLog(report, movementReportStart, Side.MINE,
                unitName(unitId, Side.MINE, false), outcome, false)) {
            report.append("我方").append(unitName(unitId, Side.MINE, false)).append("(")
                    .append(count).append(") ").append(outcome).append("\n");
        }
        return wall;
    }

    /** 历史会话没有周期起点时，从首次含伤害结算（包括零伤害）的回合恢复，避免更新后重新起算。 */
    public int firstCombatRoundFromLog(String log) {
        if (log == null || log.isBlank()) return 0;
        int round = 0;
        var roundPattern = java.util.regex.Pattern.compile("^-- 第(\\d+)回合");
        var damagePattern = java.util.regex.Pattern.compile("伤害\\d+");
        for (String line : log.split("\\R")) {
            var match = roundPattern.matcher(line);
            if (match.find()) round = Integer.parseInt(match.group(1));
            if (round > 0 && damagePattern.matcher(line).find()) return round;
        }
        return 0;
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
        return finishWorldBattle(win, survivorAttacker, survivorDefender, initialAttacker, initialDefender,
                attackerTech, Map.of(), action, defenderResources, defenderWarehouseLevel, report);
    }

    /** 战术结算读取本场守方科技，途中研发不能追溯改变仓库保护。 */
    public BattleResult finishWorldBattle(boolean win, Map<String, Integer> survivorAttacker,
                                          Map<String, Integer> survivorDefender, Map<String, Integer> initialAttacker,
                                          Map<String, Integer> initialDefender, Map<String, Integer> attackerTech,
                                          Map<String, Integer> defenderTech, String action,
                                          Map<String, Integer> defenderResources, long defenderWarehouseLevel, String report) {
        return finishBattle(win, survivorAttacker, survivorDefender, initialAttacker, initialDefender,
                attackerTech != null ? attackerTech : Collections.emptyMap(), defenderTech, action, defenderResources,
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
                                      Map<String, Integer> defenderTech,
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
            if (isHarassmentAction(act)) {
                // 与掠夺相同按半额胜利经验结算；资源奖励在所有目标上保持为空。
                expGained = expGained / 2;
                report.append("★ ").append(actionName(act)).append("胜利！本次行动不夺取资源、不占领目标。\n");
            } else if (defenderResources != null && !defenderResources.isEmpty()) {
                if ("conquer".equals(act)) {
                    // 征服: 夺取全部资源 (含黄金) - 对应 JS conquer 分支
                    for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                        plunderedResources.put(rk, defenderResources.getOrDefault(rk, 0));
                    }
                    int diamond = defenderResources.getOrDefault("diamond", 0);
                    if (diamond > 0) {
                        plunderedResources.put("diamond", diamond);
                        report.append("★ 击败最高级指挥部，缴获特殊战利品钻石 ").append(diamond).append("！\n");
                    }
                    cityConquered = true;
                    report.append("★ 征服胜利! 夺取敌方全部资源。\n");
                } else if ("plunder".equals(act)) {
                    // 掠夺: 仓库保护外的非黄金资源 - 对应 JS plunder 分支
                    int prot = calcProtectCap(defenderWarehouseLevel, defenderTech);
                    for (String rk : List.of("food", "steel", "oil", "rare")) {
                        int amount = defenderResources.getOrDefault(rk, 0);
                        plunderedResources.put(rk, Math.max(0, amount - prot));
                    }
                    plunderedResources.put("gold", 0);
                    int diamond = defenderResources.getOrDefault("diamond", 0);
                    if (diamond > 0) {
                        plunderedResources.put("diamond", diamond);
                        report.append("★ 击败最高级指挥部，缴获特殊战利品钻石 ").append(diamond).append("！\n");
                    }
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
        executeMovementPhase(mine, enemy, minePos, enemyPos, initialDist, report,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx, false, false);
    }

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
            TechCtx defenderCtx,
            boolean defenderIsJapanese,
            boolean skillRoundActive) {

        // 先按旧坐标同步分摊接触距离，再用剩余步长抵达实际前排；日志必须等双方机动结束后生成。
        Map<String, Integer> oldMinePos = new LinkedHashMap<>(minePos);
        Map<String, Integer> oldEnemyPos = new LinkedHashMap<>(enemyPos);
        moveArmy(Side.MINE, mine, enemy, minePos, oldMinePos, oldEnemyPos, initialDist,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx, skillRoundActive);
        moveArmy(Side.ENEMY, enemy, mine, enemyPos, oldMinePos, oldEnemyPos, initialDist,
                defenderOrders, attackerOrders, defenderCtx, attackerCtx, skillRoundActive);
        completeAdvanceAtContact(mine, enemy, minePos, enemyPos, oldMinePos, oldEnemyPos,
                initialDist, attackerOrders, defenderOrders, attackerCtx, defenderCtx, skillRoundActive);
        appendMovementReport(Side.MINE, mine, enemy, minePos, enemyPos, oldMinePos,
                initialDist, report, attackerOrders, defenderIsJapanese);
        appendMovementReport(Side.ENEMY, enemy, mine, minePos, enemyPos, oldEnemyPos,
                initialDist, report, defenderOrders, defenderIsJapanese);
    }

    /** 仅用回合开始的坐标规划一方机动，保持双方同步与原有速度分摊规则。 */
    private void moveArmy(Side side, Map<String, Integer> army, Map<String, Integer> foeArmy,
                          Map<String, Integer> positions, Map<String, Integer> oldMinePos,
                          Map<String, Integer> oldEnemyPos, int initialDist,
                          Map<String, UnitOrder> orders, Map<String, UnitOrder> foeOrders,
                          TechCtx ctx, TechCtx foeCtx, boolean skillRoundActive) {
        for (Map.Entry<String, Integer> e : army.entrySet()) {
            String unitId = e.getKey();
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count <= 0) continue;
            UnitStats u = getStats(unitId);
            if (u == null) continue;
            UnitOrder order = orders != null ? orders.getOrDefault(unitId, orders.get(UnitEquipmentEffects.base(unitId))) : null;
            CommandAction action = order != null ? order.action() : defaultAction(unitId);
            double spd = ctx != null ? effSpd(unitId, ctx, skillRoundActive) : u.spd();
            int rawStep = (int) (spd * 50);
            int curX = positions.getOrDefault(unitId, side == Side.MINE ? 0 : initialDist);
            if (action == CommandAction.ADVANCE) {
                int minDist = advanceDistanceToLivingFoe(side, unitId, foeArmy, oldMinePos, oldEnemyPos, initialDist);
                if (minDist == Integer.MAX_VALUE) continue;
                int step = Math.min(rawStep, minDist);
                step = limitAdvanceAtContact(side, unitId, step, rawStep, foeArmy,
                        oldMinePos, oldEnemyPos, initialDist, foeOrders, foeCtx, skillRoundActive);
                step = Math.min(step, side == Side.MINE ? Math.max(0, initialDist - curX) : curX);
                positions.put(unitId, curX + (side == Side.MINE ? step : -step));
            } else if (action == CommandAction.RETREAT) {
                int step = Math.min(rawStep, side == Side.MINE ? curX : Math.max(0, initialDist - curX));
                positions.put(unitId, curX + (side == Side.MINE ? -step : step));
            }
        }
    }

    /**
     * 混编按理论速度分摊时，受阻敌军的实际位移会更小；后续前进单位用剩余步长补齐到已接触的前排。
     * 只向双方已有单位接触的坐标补齐，并以最近存活敌军限制路程；统一读取补齐前的快照，避免遍历顺序影响。
     */
    private void completeAdvanceAtContact(Map<String, Integer> mine, Map<String, Integer> enemy,
                                           Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                           Map<String, Integer> oldMinePos, Map<String, Integer> oldEnemyPos,
                                           int initialDist, Map<String, UnitOrder> attackerOrders,
                                           Map<String, UnitOrder> defenderOrders, TechCtx attackerCtx,
                                           TechCtx defenderCtx, boolean skillRoundActive) {
        Set<Integer> contactPositions = new HashSet<>();
        for (var e : mine.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0 && getStats(e.getKey()) != null)
                contactPositions.add(minePos.getOrDefault(e.getKey(), 0));
        }
        Set<Integer> enemyPositions = new HashSet<>();
        for (var e : enemy.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0 && getStats(e.getKey()) != null)
                enemyPositions.add(enemyPos.getOrDefault(e.getKey(), initialDist));
        }
        contactPositions.retainAll(enemyPositions);
        if (contactPositions.isEmpty()) return;
        Map<String, Integer> movedMinePos = new LinkedHashMap<>(minePos);
        Map<String, Integer> movedEnemyPos = new LinkedHashMap<>(enemyPos);
        advanceRearUnitsToContact(Side.MINE, mine, enemy, minePos, oldMinePos, movedMinePos, movedEnemyPos,
                initialDist, contactPositions, attackerOrders, attackerCtx, skillRoundActive);
        advanceRearUnitsToContact(Side.ENEMY, enemy, mine, enemyPos, oldEnemyPos, movedMinePos, movedEnemyPos,
                initialDist, contactPositions, defenderOrders, defenderCtx, skillRoundActive);
    }

    /** 补齐只消耗本回合尚未使用的机动预算；待命、后退、零速单位均不会被吸附到前排。 */
    private void advanceRearUnitsToContact(Side side, Map<String, Integer> army, Map<String, Integer> foeArmy,
                                           Map<String, Integer> positions, Map<String, Integer> oldPositions,
                                           Map<String, Integer> movedMinePos, Map<String, Integer> movedEnemyPos,
                                           int initialDist, Set<Integer> contactPositions,
                                           Map<String, UnitOrder> orders, TechCtx ctx, boolean skillRoundActive) {
        for (var e : army.entrySet()) {
            String unitId = e.getKey();
            if (e.getValue() == null || e.getValue() <= 0 || getStats(unitId) == null) continue;
            UnitOrder order = orders != null ? orders.getOrDefault(unitId, orders.get(UnitEquipmentEffects.base(unitId))) : null;
            if ((order != null ? order.action() : defaultAction(unitId)) != CommandAction.ADVANCE) continue;
            int oldX = oldPositions.getOrDefault(unitId, side == Side.MINE ? 0 : initialDist);
            int curX = positions.getOrDefault(unitId, oldX);
            int rawStep = (int) ((ctx != null ? effSpd(unitId, ctx, skillRoundActive) : getStats(unitId).spd()) * 50);
            int remainingStep = Math.max(0, rawStep - Math.abs(curX - oldX));
            int gap = nearestLivingFoeDistance(side, unitId, foeArmy, movedMinePos, movedEnemyPos, initialDist);
            if (gap == Integer.MAX_VALUE) continue;
            int contactX = curX + (side == Side.MINE ? gap : -gap);
            // 必须是前进方向上最近敌军所在的真实接触点，不能跨过未接触的前排追逐更远目标。
            if (!contactPositions.contains(contactX)) continue;
            int step = Math.min(remainingStep, gap);
            positions.put(unitId, curX + (side == Side.MINE ? step : -step));
        }
    }

    /** 战报中的距敌/距我使用双方最终坐标；接触前排不等于接触本次攻击目标。 */
    private void appendMovementReport(Side side, Map<String, Integer> army, Map<String, Integer> foeArmy,
                                      Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                      Map<String, Integer> oldPositions, int initialDist, StringBuilder report,
                                      Map<String, UnitOrder> orders, boolean defenderIsJapanese) {
        Map<String, Integer> positions = side == Side.MINE ? minePos : enemyPos;
        String prefix = side == Side.MINE ? "我方" : "敌方";
        for (Map.Entry<String, Integer> e : army.entrySet()) {
            String unitId = e.getKey();
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count <= 0 || getStats(unitId) == null) continue;
            UnitOrder order = orders != null ? orders.getOrDefault(unitId, orders.get(UnitEquipmentEffects.base(unitId))) : null;
            CommandAction action = order != null ? order.action() : defaultAction(unitId);
            int oldX = oldPositions.getOrDefault(unitId, side == Side.MINE ? 0 : initialDist);
            int newX = positions.getOrDefault(unitId, oldX);
            int step = Math.abs(newX - oldX);
            report.append(prefix).append(unitName(unitId, side, defenderIsJapanese)).append("(").append(count).append(") ");
            if (action == CommandAction.ADVANCE) {
                int minDist = nearestLivingFoeDistance(side, unitId, foeArmy, minePos, enemyPos, initialDist);
                if (minDist == Integer.MAX_VALUE) {
                    report.append("[前进] 无存活敌军，保持坐标").append(newX);
                } else {
                    if (step > 0) report.append("[前进] 推进").append(step).append(" -> 坐标").append(newX);
                    else report.append(minDist == 0 ? "[前进] 已接触前排 坐标" : "[前进] 未能推进 坐标").append(newX);
                    report.append(side == Side.MINE ? " (距敌" : " (距我").append(minDist).append(")");
                }
            } else if (action == CommandAction.RETREAT) {
                boolean atBaseline = side == Side.MINE ? oldX <= 0 : oldX >= initialDist;
                if (atBaseline) report.append("[后退] 已达阵地底线(坐标").append(side == Side.MINE ? 0 : initialDist).append(") 退无可退");
                else report.append("[后退] 撤退").append(step).append(" -> 坐标").append(newX);
            } else {
                report.append("[待命] 坚守阵地 坐标").append(newX);
            }
            report.append("\n");
        }
    }

    // ========================================================================
    // 阶段二: 战术交火阶段 (Combat Phase)
    // 按射程降序、速度降序依次开火；优先集火目标，支持边打边退
    // ========================================================================

    /** 机动完成后首次出现可开火目标即记为交战；所有战斗技能在该回合及此后每隔两回合共同生效。 */
    private int executeCombatPhase(
            Map<String, Integer> mine,
            Map<String, Integer> enemy,
            Map<String, Integer> minePos,
            Map<String, Integer> enemyPos,
            int initialDist,
            StringBuilder report,
            int movementReportStart,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            TechCtx attackerCtx,
            TechCtx defenderCtx,
            boolean officerActive,
            int round,
            int firstCombatRound) {
        return executeCombatPhase(mine, enemy, minePos, enemyPos, initialDist, report,
                movementReportStart, attackerOrders, defenderOrders, attackerCtx, defenderCtx, officerActive, round, firstCombatRound, false);
    }

    private int executeCombatPhase(
            Map<String, Integer> mine,
            Map<String, Integer> enemy,
            Map<String, Integer> minePos,
            Map<String, Integer> enemyPos,
            int initialDist,
            StringBuilder report,
            int movementReportStart,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            TechCtx attackerCtx,
            TechCtx defenderCtx,
            boolean officerActive,
            int round,
            int firstCombatRound,
            boolean defenderIsJapanese) {
        return executeCombatPhase(mine, enemy, minePos, enemyPos, initialDist, report, movementReportStart,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx, officerActive, round, firstCombatRound,
                defenderIsJapanese, null);
    }

    private int executeCombatPhase(
            Map<String, Integer> mine,
            Map<String, Integer> enemy,
            Map<String, Integer> minePos,
            Map<String, Integer> enemyPos,
            int initialDist,
            StringBuilder report,
            int movementReportStart,
            Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders,
            TechCtx attackerCtx,
            TechCtx defenderCtx,
            boolean officerActive,
            int round,
            int firstCombatRound,
            boolean defenderIsJapanese, WallCombatState wallCombat) {
        return executeCombatPhase(mine, enemy, minePos, enemyPos, initialDist, report, movementReportStart,
                attackerOrders, defenderOrders, attackerCtx, defenderCtx, officerActive, round, firstCombatRound,
                defenderIsJapanese, wallCombat, false);
    }

    private int executeCombatPhase(Map<String, Integer> mine, Map<String, Integer> enemy,
            Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist,
            StringBuilder report, int movementReportStart, Map<String, UnitOrder> attackerOrders,
            Map<String, UnitOrder> defenderOrders, TechCtx attackerCtx, TechCtx defenderCtx,
            boolean officerActive, int round, int firstCombatRound, boolean defenderIsJapanese,
            WallCombatState wallCombat, boolean ambushFirstRound) {
        List<ActionEntry> order = buildOrder(mine, enemy, attackerCtx, defenderCtx, round);
        if (firstCombatRound == 0) {
            // 与实际开火使用同一选敌规则，不能仅凭距离或伤亡判断交战；首发伤害也必须享受技能。
            for (ActionEntry entry : order) {
                Map<String, Integer> ownArmy = entry.side == Side.MINE ? mine : enemy;
                if (ownArmy.getOrDefault(entry.id, 0) <= 0) continue;
                Map<String, Integer> foeArmy = entry.side == Side.MINE ? enemy : mine;
                TechCtx ctx = entry.side == Side.MINE ? attackerCtx : defenderCtx;
                Map<String, UnitOrder> orders = entry.side == Side.MINE ? attackerOrders : defenderOrders;
                UnitOrder unitOrder = orders != null ? orders.getOrDefault(entry.id, orders.get(UnitEquipmentEffects.base(entry.id))) : null;
                if (pickCombatTarget(entry.id, entry.side, foeArmy, minePos, enemyPos, initialDist, ctx,
                        unitOrder != null ? unitOrder.focusTarget() : null) != null) {
                    firstCombatRound = round;
                    break;
                }
            }
        }
        boolean skillRoundActive = isSkillRoundActive(round, firstCombatRound);
        // 首次交火在索敌后才确定，属性与技能共用该起点，首发攻防也必须立即享受将领加成。
        officerActive = skillRoundActive;
        if (practiceEvents != null) {
            for (Side side : Side.values()) {
                TechCtx ctx = side == Side.MINE ? attackerCtx : defenderCtx;
                event("OFFICER_ATTRIBUTES", side, "", "", Map.of("active", officerActive,
                        "military", ctx.commanderMil, "defense", ctx.commanderDef,
                        "firstCombatRound", firstCombatRound, "cycle", "E/E+3/E+6"));
                for (String skill : List.of("frenzy", "bulwark", "blitz", "suppress", "pierce", "counter", "learn", "borrow_armor")) {
                    int level = ctx.skills.getOrDefault(skill, 0);
                    event("SKILL_WINDOW", side, "", "", Map.of("skill", skill, "level", level,
                            "firstCombatRound", firstCombatRound, "active", skillRoundActive && level > 0,
                            "reason", level == 0 ? "NO_COMMANDER_OR_SKILL" : firstCombatRound == 0 ? "NOT_ENGAGED" :
                                    !skillRoundActive ? "OUTSIDE_SKILL_WINDOW" : "WINDOW_OPEN"));
                }
            }
        }
        // 首次交战在机动结束后确认；闪电突击立即参与交火先手，已完成的机动不追溯重算。
        if (skillRoundActive) order = buildOrder(mine, enemy, attackerCtx, defenderCtx, round, true);
        // 稳定排序只提升伏击方的主动行动；同侧仍保留射程/速度顺序，受击反击沿用原规则。
        if (ambushFirstRound) order.sort(Comparator.comparingInt(entry -> entry.side == Side.ENEMY ? 0 : 1));
        for (int i = 0; i < order.size() && practiceEvents != null; i++) {
            ActionEntry entry = order.get(i);
            TechCtx ctx = entry.side == Side.MINE ? attackerCtx : defenderCtx;
            event("INITIATIVE", entry.side, entry.id, "", Map.of("order", i + 1, "range", entry.range, "speed", entry.spd,
                    "blitzMultiplier", 1 + (skillRoundActive ? skillBonus(ctx.skills, "blitz") : 0)));
        }
        StringBuilder bonusLog = new StringBuilder();
        String mineBonus = buildCommanderBonusLog("我方", attackerCtx, round, skillRoundActive);
        String enemyBonus = buildCommanderBonusLog("敌方", defenderCtx, round, skillRoundActive);
        if (!mineBonus.isEmpty()) bonusLog.append(mineBonus).append("\n");
        if (!enemyBonus.isEmpty()) bonusLog.append(enemyBonus).append("\n");
        report.insert(movementReportStart, bonusLog);
        movementReportStart += bonusLog.length();
        // 在本回合交火开始时记录存活同名兵种，避免击杀顺序影响学习对象。
        Set<String> mineLearningSources = skillRoundActive ? snapshotLivingTroopIds(enemy) : Collections.emptySet();
        Set<String> enemyLearningSources = skillRoundActive ? snapshotLivingTroopIds(mine) : Collections.emptySet();
        Set<String> mineArmorSources = skillRoundActive ? snapshotLivingTroopIds(mine) : Collections.emptySet();
        Set<String> enemyArmorSources = skillRoundActive ? snapshotLivingTroopIds(enemy) : Collections.emptySet();
        for (ActionEntry a : order) {
            Map<String, Integer> myA = a.side == Side.MINE ? mine : enemy;
            Map<String, Integer> foe = a.side == Side.MINE ? enemy : mine;
            if (myA.getOrDefault(a.id, 0) <= 0) continue;
            if (allDead(foe) && !(a.side == Side.MINE && wallCombat != null && wallCombat.wall.standing())) continue;

            TechCtx actCtx = a.side == Side.MINE ? attackerCtx : defenderCtx;
            TechCtx foeCtx = a.side == Side.MINE ? defenderCtx : attackerCtx;
            Set<String> learningSources = a.side == Side.MINE ? mineLearningSources : enemyLearningSources;
            // 普通防御读取被攻击方的同名兵种；反击防御读取被反击方的同名兵种。
            Set<String> armorSources = a.side == Side.MINE ? enemyArmorSources : mineArmorSources;
            Set<String> counterArmorSources = a.side == Side.MINE ? mineArmorSources : enemyArmorSources;

            UnitOrder uOrder = a.side == Side.MINE
                    ? (attackerOrders != null ? attackerOrders.getOrDefault(a.id, attackerOrders.get(UnitEquipmentEffects.base(a.id))) : null)
                    : (defenderOrders != null ? defenderOrders.getOrDefault(a.id, defenderOrders.get(UnitEquipmentEffects.base(a.id))) : null);
            String focusTarget = uOrder != null ? uOrder.focusTarget() : null;

            fireUnitCombat(a.id, myA, foe, minePos, enemyPos, initialDist, report, a.side,
                    actCtx, foeCtx, officerActive, skillRoundActive,
                    learningSources, armorSources, counterArmorSources, focusTarget, false,
                    movementReportStart, defenderIsJapanese, wallCombat);
        }
        reorderMovementLogsByCombatOrder(report, movementReportStart, order, defenderIsJapanese);
        return firstCombatRound;
    }

    /** 指定射程内目标优先；未指定时先攻击同兵种，否则攻击最近目标。 */
    private String pickCombatTarget(String attackerId, Side side, Map<String, Integer> foeArmy,
                                    Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                    int initialDist, TechCtx ctx, String focusTarget) {
        if (focusTarget != null) {
            for (String candidate : foeArmy.keySet()) {
                if (UnitEquipmentEffects.base(candidate).equals(UnitEquipmentEffects.base(focusTarget))
                        && foeArmy.getOrDefault(candidate, 0) > 0 && baseAttack(attackerId, candidate) > 0
                        && getUnitDistToFoe(side, attackerId, candidate, minePos, enemyPos, initialDist) <= effectiveRange(attackerId, ctx)) return candidate;
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
                                boolean officerActive,
                                String focusTarget) {
        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side,
                actCtx, foeCtx, officerActive, officerActive,
                snapshotLivingTroopIds(foeArmy), snapshotLivingTroopIds(myArmy),
                snapshotLivingTroopIds(foeArmy), focusTarget, false, -1, false);
    }

    /**
     * 将交火阶段结果合并到本回合对应的兵种日志，避免同一兵种的多段攻击结果被其他兵种隔开。
     *
     * @param report 当前回合战报。
     * @param movementReportStart 本回合机动日志的起始位置。
     * @param side 行动方。
     * @param unitName 单位显示名称。
     * @param outcome 要追加的交火结果。
     * @param advanceOnly 是否仅允许追加到前进日志；否则也允许追加到该兵种已有的交火日志。
     * @return 是否找到匹配的机动日志并成功追加。
     */
    private boolean appendCombatOutcomeToMovementLog(StringBuilder report, int movementReportStart,
                                                     Side side, String unitName, String outcome,
                                                     boolean advanceOnly) {
        if (movementReportStart < 0) return false;
        String prefix = (side == Side.MINE ? "我方" : "敌方") + unitName + "(";
        int cursor = movementReportStart;
        int lineEnd;
        int matchedLineEnd = -1;
        while ((lineEnd = report.indexOf("\n", cursor)) >= 0) {
            String line = report.substring(cursor, lineEnd);
            boolean movementLine = line.contains(" [前进] ")
                    || line.contains(" [后退] ")
                    || line.contains(" [待命] ");
            if ((!advanceOnly || (movementLine && line.contains(" [前进] "))) && line.startsWith(prefix)) {
                matchedLineEnd = lineEnd;
            }
            cursor = lineEnd + 1;
        }
        if (matchedLineEnd < 0) return false;
        report.insert(matchedLineEnd, "；" + outcome);
        return true;
    }

    private void reorderMovementLogsByCombatOrder(StringBuilder report, int movementReportStart,
                                                  List<ActionEntry> order, boolean defenderIsJapanese) {
        if (movementReportStart < 0 || movementReportStart >= report.length() || order.isEmpty()) return;

        String[] lines = report.substring(movementReportStart).split("\\n", -1);
        List<Integer> movementLineIndexes = new ArrayList<>();
        List<String> movementLines = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (isMovementLogLine(lines[index])) {
                movementLineIndexes.add(index);
                movementLines.add(lines[index]);
            }
        }
        if (movementLines.size() < 2) return;

        Map<String, Integer> combatRanks = new HashMap<>();
        for (int index = 0; index < order.size(); index++) {
            ActionEntry action = order.get(index);
            UnitStats unit = getStats(action.id);
            if (unit == null) continue;
            String prefix = (action.side == Side.MINE ? "我方" : "敌方") + unitName(action.id, action.side, defenderIsJapanese) + "(";
            combatRanks.putIfAbsent(prefix, index);
        }
        // 未开火单位的机动发生在交火前，不能排到击毁它的攻击记录之后。
        movementLines.sort(Comparator.comparingInt((String line) -> line.contains(" 击毁") ? 1 : 0)
                .thenComparingInt(line -> combatRanks.getOrDefault(
                        movementLogPrefix(line), Integer.MAX_VALUE)));
        for (int index = 0; index < movementLineIndexes.size(); index++) {
            lines[movementLineIndexes.get(index)] = movementLines.get(index);
        }
        report.replace(movementReportStart, report.length(), String.join("\n", lines));
    }

    private boolean isMovementLogLine(String line) {
        return line.contains(" [前进] ") || line.contains(" [后退] ") || line.contains(" [待命] ");
    }

    private String movementLogPrefix(String line) {
        int countStart = line.indexOf('(');
        return countStart < 0 ? line : line.substring(0, countStart + 1);
    }

    /**
     * 执行单个单位的交火逻辑；步兵贴脸为 2 倍，轻重坦和特种兵为 1.5 倍。
     * 主动及余伤攻击逐目标判断接触距离，在最终直接伤害上结算加成，不影响反击。
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
                                boolean officerActive,
                                boolean skillRoundActive,
                                Set<String> learningSources,
                                Set<String> armorSources,
                                Set<String> counterArmorSources,
                                String focusTarget,
                                boolean isCounter,
                                int movementReportStart) {
        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side,
                actCtx, foeCtx, officerActive, skillRoundActive,
                learningSources, armorSources, counterArmorSources, focusTarget, isCounter,
                movementReportStart, false);
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
                                boolean officerActive,
                                boolean skillRoundActive,
                                Set<String> learningSources,
                                Set<String> armorSources,
                                Set<String> counterArmorSources,
                                String focusTarget,
                                boolean isCounter,
                                int movementReportStart,
                                boolean defenderIsJapanese) {
        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side,
                actCtx, foeCtx, officerActive, skillRoundActive, learningSources, armorSources,
                counterArmorSources, focusTarget, isCounter, movementReportStart, defenderIsJapanese, null);
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
                                boolean officerActive,
                                boolean skillRoundActive,
                                Set<String> learningSources,
                                Set<String> armorSources,
                                Set<String> counterArmorSources,
                                String focusTarget,
                                boolean isCounter,
                                int movementReportStart,
                                boolean defenderIsJapanese, WallCombatState wallCombat) {
        UnitStats u = getStats(unitId);
        if (u == null) return;
        int count = myArmy.getOrDefault(unitId, 0);
        if (count <= 0) return;

        String uName = unitName(unitId, side, defenderIsJapanese);
        String sidePrefix = side == Side.MINE ? "我方" : "敌方";

        // 寻找射程内目标
        String target = pickCombatTarget(unitId, side, foeArmy, minePos, enemyPos, initialDist, actCtx, focusTarget);
        if (side == Side.MINE && wallCombat != null && allDead(foeArmy)) {
            wallCombat.wall = attackWallUnit(unitId, count, count, minePos, initialDist, actCtx,
                    officerActive, skillRoundActive, wallCombat.wall, report, movementReportStart, false);
            return;
        }
        if (target == null) {
            event("NO_FIRE", side, unitId, "", Map.of("reason", "NO_VALID_RANGE_TARGET"));
            int minDist = advanceDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
            if (minDist != Integer.MAX_VALUE && minDist > effectiveRange(unitId, actCtx)) {
                return;
            }
            String noFireReason;
            if (minDist == Integer.MAX_VALUE) {
                noFireReason = "无可攻击目标，未开火";
            } else {
                noFireReason = "当前目标不可攻击，未开火";
            }
            if (!appendCombatOutcomeToMovementLog(report, movementReportStart, side, uName, noFireReason, false)) {
                report.append(sidePrefix).append(uName).append("(").append(count).append(") ")
                        .append(noFireReason).append("\n");
            }
            return;
        }

        // 射程内开火
        double attackBonus = actCtx == null ? 1
                : attackBonus(u.cat(), actCtx.tech, actCtx.skills, actCtx.commanderMil,
                        officerActive, skillRoundActive) * actCtx.commandBonus.attackMul();
        if (skillRoundActive && foeCtx != null) attackBonus *= 1 - skillBonus(foeCtx.skills, "suppress");
        double pierce = !skillRoundActive || actCtx == null ? 0 : skillBonus(actCtx.skills, "pierce");
        double firepower = actionFirepowerMultiplier(actCtx == null ? null : actCtx.tech);
        double totalActions = count;
        double remainingActions = totalActions;
        boolean firstTarget = true;

        while (target != null && remainingActions > 1e-9) {
            UnitStats tU = getStats(target);
            Side foeSide = (side == Side.MINE ? Side.ENEMY : Side.MINE);
            String tName = unitName(target, foeSide, defenderIsJapanese);
            double def = foeCtx == null ? tU.def()
                    : effDef(target, foeCtx.tech, foeCtx.skills, foeCtx.commanderDef, officerActive, skillRoundActive, skillRoundActive,
                    actCtx, armorSources, foeCtx.commandBonus);
            def = Math.max(0, def * (1 - pierce));
            double cm = counterMul(unitId, target);
            double targetAttack = baseAttack(unitId, target) * attackBonus * UnitEquipmentEffects.attack(actCtx == null ? null : actCtx.tech, unitId, target);
            double learnedAttack = learningBonus(unitId, target, actCtx, foeCtx,
                    skillRoundActive, learningSources, isCounter);
            targetAttack += learnedAttack;
            // 余伤切换目标后重新检查距离，不能把前排的接触加成带给尚未接触的后排。
            double pointBlankMultiplier = getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist) == 0
                    ? POINT_BLANK_MULTIPLIERS.getOrDefault(UnitEquipmentEffects.base(unitId), 1.0) : 1.0;
            double damagePerAction = targetAttack * cm * 100.0 / (100.0 + 5 * def)
                    * pointBlankMultiplier * UnitEquipmentEffects.damage(foeCtx == null ? null : foeCtx.tech, target, unitId) * firepower;
            // 每份攻击额度只乘一次火力密度；击毁消耗按增强后的伤害计算，余伤切换不重置额度。
            double hpPer = Math.max(1, foeCtx == null ? tU.hp() : effHp(target, foeCtx.tech) * foeCtx.commandBonus.hpMul());
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
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("damage", appliedDamage); effect.put("kills", kills); effect.put("attack", targetAttack);
            effect.put("firepowerMultiplier", firepower);
            effect.put("rawFirepower", targetAttack * totalActions * firepower);
            effect.put("distance", getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist));
            effect.put("range", effectiveRange(unitId, actCtx)); effect.put("defense", def); effect.put("hp", hpPer);
            effect.put("frenzyMultiplier", 1 + (skillRoundActive && actCtx != null ? skillBonus(actCtx.skills, "frenzy") : 0));
            effect.put("suppression", skillRoundActive && foeCtx != null ? skillBonus(foeCtx.skills, "suppress") : 0);
            effect.put("pierce", pierce); effect.put("learnedAttack", learnedAttack);
            effect.put("bulwarkMultiplier", 1 + (skillRoundActive && foeCtx != null ? skillBonus(foeCtx.skills, "bulwark") : 0));
            effect.put("borrowedDefense", foeCtx == null ? 0 : borrowedArmorBonus(target, foeCtx.skills, skillRoundActive, actCtx, armorSources));
            effect.put("learnReason", actCtx == null || skillBonus(actCtx.skills, "learn") == 0 ? "NO_COMMANDER_OR_SKILL" : !skillRoundActive ? "OUTSIDE_SKILL_WINDOW" : learningSources.contains(UnitEquipmentEffects.base(unitId)) ? "APPLIED_IF_LEARNED" : "NO_SAME_UNIT_REFERENCE");
            effect.put("armorReason", foeCtx == null || skillBonus(foeCtx.skills, "borrow_armor") == 0 ? "NO_COMMANDER_OR_SKILL" : !skillRoundActive ? "OUTSIDE_SKILL_WINDOW" : skillBonus(foeCtx.skills, "bulwark") > 0 ? "BULWARK_PRIORITY" : armorSources.contains(UnitEquipmentEffects.base(target)) ? "APPLIED_IF_LEARNED" : "NO_SAME_UNIT_REFERENCE");
            event("FIRE", side, unitId, target, effect);
            StringBuilder attackOutcome = new StringBuilder();
            attackOutcome.append(sidePrefix).append(uName).append("(").append(count).append(")")
                    .append(firstTarget ? verb(unitId) : "余伤攻击")
                    .append(side == Side.MINE ? "敌" : "我").append(tName)
                    .append("(").append(beforeKill).append(")");
            List<String> tags = new ArrayList<>();
            if (cm > 1.0001) tags.add("倍率×" + cm + " 相克");
            else if (cm < 0.9999) tags.add("倍率×" + cm + " 火力受限");
            if (pointBlankMultiplier > 1.0) tags.add("贴脸×" + pointBlankMultiplier);
            if (firepower > 1.0) tags.add("火力密度×" + firepower);
            if ("htank".equals(UnitEquipmentEffects.base(target))) tags.add("前排承伤");
            if (firstTarget && focusTarget != null && focusTarget.equals(target)) tags.add("指定集火");
            double gearReduction = 1 - UnitEquipmentEffects.damage(foeCtx == null ? null : foeCtx.tech, target, unitId);
            if (gearReduction > 0) tags.add("装备减伤" + percent(gearReduction) + "%");
            if (pierce > 0) tags.add("破甲" + percent(pierce) + "%");
            if (learnedAttack > 0) tags.add("师夷长技+" + Math.round(learnedAttack) + "攻击");
            if (!tags.isEmpty()) {
                attackOutcome.append(" [").append(String.join(" ", tags)).append("]");
            }
            attackOutcome.append(" ").append(BattleRules.domainLabel(target)).append("攻击").append(Math.round(targetAttack));
            if (firstTarget) attackOutcome.append(" 本次原始火力").append(Math.round(targetAttack * totalActions * firepower));
            attackOutcome.append(" 伤害").append(Math.round(appliedDamage)).append(" 击毁").append(kills)
                    .append(" 剩余攻击额度").append(Math.round(100 * remainingActions / totalActions)).append("%");
            if ("rocket".equals(UnitEquipmentEffects.base(unitId))) {
                attackOutcome.append(" 有效射程").append(effectiveRange(unitId, actCtx));
            }
            attackOutcome.append(" 目标距离").append(getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist));
            // 首次开火优先接到前进行；后续余伤追加到同一兵种已有日志，避免被其他兵种隔开。
            String combatPrefix = sidePrefix + uName + "(" + count + ")";
            String attackDetails = attackOutcome.substring(combatPrefix.length());
            int unitLogStart = Math.max(0, movementReportStart);
            boolean appendToUnitLog = appendCombatOutcomeToMovementLog(report, unitLogStart, side,
                    uName, attackDetails, firstTarget);
            if (!appendToUnitLog) {
                report.append(attackOutcome).append("\n");
            }

            // --- 绝境反击 (Counterattack) ---
            // 反击与将领属性共用首次交火起算的周期；仍须受击存活且能够还击。
            if (!isCounter && foeCtx != null && skillBonus(foeCtx.skills, "counter") > 0) {
                int counterDistance = getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist);
                String reason = !skillRoundActive ? "OUTSIDE_SKILL_WINDOW" : remainingTarget <= 0 ? "ELIMINATED_BEFORE_COUNTER"
                        : myArmy.getOrDefault(unitId, 0) <= 0 ? "NO_LIVING_ATTACKER" : baseAttack(target, unitId) <= 0 ? "NO_APPLICABLE_ATTACK_DOMAIN"
                        : counterDistance > effectiveRange(target, foeCtx) ? "NO_VALID_RANGE_TARGET" : "";
                if (!reason.isEmpty()) event("NO_COUNTER", foeSide, target, unitId, Map.of("skill", "counter", "reason", reason,
                        "distance", counterDistance, "range", effectiveRange(target, foeCtx)));
            }
            if (skillRoundActive && !isCounter && foeCtx != null && remainingTarget > 0 && myArmy.getOrDefault(unitId, 0) > 0) {
                double counterRate = skillBonus(foeCtx.skills, "counter");
                if (counterRate > 0 && baseAttack(target, unitId) > 0) {
                    int targetRange = effectiveRange(target, foeCtx);
                    int dist = getUnitDistToFoe(side, unitId, target, minePos, enemyPos, initialDist);
                    if (dist <= targetRange) {
                        double cAtkBonus = attackBonus(tU.cat(), foeCtx.tech, foeCtx.skills, foeCtx.commanderMil,
                                officerActive, skillRoundActive) * foeCtx.commandBonus.attackMul();
                        if (actCtx != null) cAtkBonus *= 1 - skillBonus(actCtx.skills, "suppress");
                        double cPierce = skillBonus(foeCtx.skills, "pierce");
                        double cDef = actCtx == null ? u.def()
                                : effDef(unitId, actCtx.tech, actCtx.skills, actCtx.commanderDef, officerActive, skillRoundActive, skillRoundActive,
                                foeCtx, counterArmorSources, actCtx.commandBonus);
                        cDef = Math.max(0, cDef * (1 - cPierce));
                        double cCm = counterMul(target, unitId);
                        double cBaseAtk = baseAttack(target, unitId) * cAtkBonus * UnitEquipmentEffects.attack(foeCtx.tech, target, unitId);
                        // 反击使用反击方自己的火力科技，与反击比例独立相乘，不借用进攻方倍率或贴脸加成。
                        double cFirepower = actionFirepowerMultiplier(foeCtx.tech);
                        double cDamagePerAction = cBaseAtk * cCm * 100.0 / (100.0 + 5 * cDef) * UnitEquipmentEffects.damage(actCtx == null ? null : actCtx.tech, unitId, target) * cFirepower;
                        double cHpPer = Math.max(1, actCtx == null ? u.hp() : effHp(unitId, actCtx.tech) * actCtx.commandBonus.hpMul());
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
                        event("COUNTER", foeSide, target, unitId, Map.of("skill", "counter", "damage", cAppliedDamage,
                                "kills", cKills, "rate", counterRate, "distance", dist, "range", targetRange,
                                "firepowerMultiplier", cFirepower, "rawFirepower", cBaseAtk * remainingTarget * counterRate * cFirepower));

                        String foeSidePrefix = foeSide == Side.MINE ? "我方" : "敌方";
                        String mySidePrefix = side == Side.MINE ? "我" : "敌";
                        report.append(foeSidePrefix).append(tName).append("(").append(remainingTarget).append(")")
                                .append(" [绝境反击] ").append(mySidePrefix).append(uName).append("(").append(cBeforeKill).append(")");
                        List<String> cTags = new ArrayList<>();
                        if (cFirepower > 1.0) cTags.add("火力密度×" + cFirepower);
                        if (cCm > 1.0001) cTags.add("倍率×" + cCm + " 相克");
                        else if (cCm < 0.9999) cTags.add("倍率×" + cCm + " 火力受限");
                        double cGearReduction = 1 - UnitEquipmentEffects.damage(actCtx == null ? null : actCtx.tech, unitId, target);
                        if (cGearReduction > 0) cTags.add("装备减伤" + percent(cGearReduction) + "%");
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
        if (side == Side.MINE && !isCounter && wallCombat != null && allDead(foeArmy)
                && myArmy.getOrDefault(unitId, 0) > 0 && remainingActions > 1e-9) {
            wallCombat.wall = attackWallUnit(unitId, count, remainingActions, minePos, initialDist, actCtx,
                    officerActive, skillRoundActive, wallCombat.wall, report, movementReportStart, true);
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
        simAct(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side, actCtx, foeCtx, officerActive);
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
                           boolean officerActive) {
        UnitStats u = getStats(unitId);
        if (u == null) return false;
        int count = myArmy.getOrDefault(unitId, 0);
        if (count <= 0) return false;

        String target = pickCombatTarget(unitId, side, foeArmy, minePos, enemyPos, initialDist, actCtx, null);
        if (target == null) {
            int minDist = advanceDistanceToLivingFoe(side, unitId, foeArmy, minePos, enemyPos, initialDist);
            if (minDist == Integer.MAX_VALUE) return false;
            if (!u.autoAdvance()) return false;
            double spd = actCtx != null ? effSpd(unitId, actCtx) : u.spd();
            int step = Math.min((int) (spd * 50), minDist);
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

        fireUnitCombat(unitId, myArmy, foeArmy, minePos, enemyPos, initialDist, report, side, actCtx, foeCtx, officerActive, null);
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
    double attackBonus(String cat, Map<String, Integer> tech, Map<String, Integer> skills,
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

    /**
     * 计算师夷长技在当前攻击领域提供的临时攻击值。
     *
     * 同名敌军是否存活以交火阶段开始时的快照为准，参考值只读取基础攻击、攻击科技和军事属性，
     * 不复制临时技能或压制效果；反击不享受此加成，防止与绝境反击在同一回合形成双重爆发。
     */
    double learningBonus(String unitId, String targetId, TechCtx actCtx, TechCtx foeCtx,
                                 boolean learningActive, Set<String> learningSources, boolean isCounter) {
        if (!learningActive || isCounter || actCtx == null || foeCtx == null
                || !learningSources.contains(UnitEquipmentEffects.base(unitId)) || !GameData.UNITS.containsKey(UnitEquipmentEffects.base(unitId))) {
            return 0;
        }
        UnitStats unit = getStats(unitId);
        if (unit == null) return 0;

        // 按当前目标领域读取敌方同名兵种攻击，并只保留可稳定复现的永久成长倍率。
        double enemyStableAttack = baseAttack(unitId, targetId)
                * atkMul(unit.cat(), foeCtx.tech, foeCtx.commanderMil) * foeCtx.commandBonus.attackMul();
        double learnedAttack = enemyStableAttack * skillBonus(actCtx.skills, "learn");
        // 封顶同样以敌方攻击为基准，保留高养成敌军带来高学习收益的设计。
        return Math.min(learnedAttack, enemyStableAttack * 0.30);
    }

    /**
     * 创建本回合可供师夷长技参考的存活兵种快照。
     *
     * 城防不属于兵种，不能作为学习来源；返回集合不会随本回合的击杀而改变。
     */
    private Set<String> snapshotLivingTroopIds(Map<String, Integer> army) {
        Set<String> troopIds = new HashSet<>();
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0 && GameData.UNITS.containsKey(UnitEquipmentEffects.base(entry.getKey()))) {
                troopIds.add(UnitEquipmentEffects.base(entry.getKey()));
            }
        }
        return troopIds;
    }

    /** 防御科技双方常驻；防御属性、坚守阵地和借甲御敌按各自回合生效。 */
    private double effDef(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderDef, boolean commanderActive,
                          boolean bulwarkActive, boolean armorLearningActive,
                          TechCtx armorSourceCtx, Set<String> armorSources) {
        return effDef(unitId, tech, skills, commanderDef, commanderActive, bulwarkActive,
                armorLearningActive, armorSourceCtx, armorSources, CommandBonus.NONE);
    }

    /** 司令官仅放大自身永久防御，借甲御敌产生的临时防御不重复放大。 */
    double effDef(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderDef, boolean commanderActive,
                          boolean bulwarkActive, boolean armorLearningActive,
                          TechCtx armorSourceCtx, Set<String> armorSources,
                          CommandBonus commandBonus) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        double multiplier = defMul(tech, commanderActive ? commanderDef : 0);
        double defense = u.def() * UnitEquipmentEffects.mul(tech, unitId, "defense") * multiplier * commandBonus.defenseMul();
        if (bulwarkActive) defense *= 1 + skillBonus(skills, "bulwark");
        return defense + borrowedArmorBonus(unitId, skills, armorLearningActive,
                armorSourceCtx, armorSources);
    }

    /** 非战斗战力概览不模拟借甲御敌的敌军快照。 */
    private double effDef(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          int commanderDef, boolean commanderActive,
                          boolean bulwarkActive) {
        return effDef(unitId, tech, skills, commanderDef, commanderActive,
                bulwarkActive, false, null, Collections.emptySet());
    }

    /**
     * 计算借甲御敌在当前回合提供的临时防御值。
     *
     * 参考对象必须是交火阶段开始时存活的同名敌军；仅复制其基础防御与防御科技，
     * 不继承将领属性或临时技能。旧存档若同时拥有坚守阵地，则以坚守阵地优先。
     */
    private double borrowedArmorBonus(String unitId, Map<String, Integer> skills,
                                      boolean armorLearningActive, TechCtx armorSourceCtx,
                                      Set<String> armorSources) {
        if (!armorLearningActive || armorSourceCtx == null || !armorSources.contains(UnitEquipmentEffects.base(unitId))
                || !GameData.UNITS.containsKey(UnitEquipmentEffects.base(unitId)) || skillBonus(skills, "bulwark") > 0) {
            return 0;
        }
        UnitStats unit = getStats(unitId);
        if (unit == null) return 0;

        // 临时加成不参与借鉴，防止额外防御再次复制为技能收益。
        double enemyStableDefense = unit.def() * defMul(armorSourceCtx.tech);
        double borrowedDefense = enemyStableDefense * skillBonus(skills, "borrow_armor");
        // 封顶同样以敌方防御为基准，保留高养成敌军带来高借甲收益的设计。
        return Math.min(borrowedDefense, enemyStableDefense * 0.30);
    }

    /**
     * 有效生命值 - 对应 JS effHp(id)
     * <p>
     * 始终生效 (不依赖 officerActive): base * hpMul(cat)
     */
    double effHp(String unitId, Map<String, Integer> tech) {
        UnitStats u = getStats(unitId);
        if (u == null) return 1;
        return u.hp() * UnitEquipmentEffects.mul(tech, unitId, "hp") * hpMul(u.cat(), tech);
    }

    /** 常驻学识加成进入实际机动、交火先手，不参与开局距离，工事零速度保持为零。 */
    private double effSpd(String unitId, TechCtx ctx) {
        return effSpd(unitId, ctx, false);
    }

    private double effSpd(String unitId, TechCtx ctx, boolean skillRoundActive) {
        return effSpd(unitId, ctx.tech, ctx.skills, skillRoundActive) * ctx.commandBonus.speedMul();
    }

    /** 尚未交战时按科技速度计算开局距离，不能把周期闪电突击当成常驻速度。 */
    private double effSpd(String unitId, Map<String, Integer> tech, Map<String, Integer> skills) {
        return effSpd(unitId, tech, skills, false);
    }

    /** 科技速度常驻；闪电突击只在统一交战技能回合影响机动和交火先手。 */
    private double effSpd(String unitId, Map<String, Integer> tech, Map<String, Integer> skills,
                          boolean skillRoundActive) {
        UnitStats u = getStats(unitId);
        if (u == null) return 0;
        // 全部陆军共用燃烧引擎，攻击、防御等仍按原类别计算。
        String speedCat = u.cat();
        double base = u.spd() * UnitEquipmentEffects.speed(tech, unitId, true) * spdMul(speedCat, tech);
        double blitz = skillRoundActive ? skillBonus(skills, "blitz") : 0;
        return base * (1 + blitz);
    }

    // ========================================================================
    // 倍率计算 (对应 JS Core.atkMul / defMul / hpMul / spdMul)
    // ========================================================================

    /**
     * 攻击倍率 - 对应 JS Core.atkMul(cat)
     * <p>
     * (1 + 0.05*attack_tech) * (1 + cmdMil/100)，科技满级10级时提供50%基础攻击加成
     */
    private double atkMul(String cat, Map<String, Integer> tech, int commanderMil) {
        int cmdAttack = tech.getOrDefault("attack_tech", 0);
        double allMul = 1 + 0.05 * cmdAttack;
        return allMul * (1 + commanderMil / 100.0);
    }

    /**
     * 防御倍率：攻守双方使用相同规则，墙体等级不进入部队或工事防御。
     * <p>
     * (1 + 0.05*defense_tech) * (1 + cmdDef/100)
     */
    private double defMul(Map<String, Integer> tech) {
        return defMul(tech, 0);
    }

    private double defMul(Map<String, Integer> tech, int commanderDef) {
        int cmdDefense = tech.getOrDefault("defense_tech", 0);
        double allMul = 1 + 0.05 * cmdDefense;
        if (commanderDef > 0) {
            allMul *= (1 + commanderDef / 100.0);
        }
        return allMul;
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

    /** 全军行动火力常驻且与攻击独立相乘；旧快照缺项为零级，非法等级限制在 0～10。 */
    private double actionFirepowerMultiplier(Map<String, Integer> tech) {
        Integer level = tech == null ? null : tech.get("fire_density");
        return 1 + 0.05 * Math.max(0, Math.min(10, level == null ? 0 : level));
    }

    /**
     * 速度倍率 - 对应 JS Core.spdMul(cat)
     * <p>
     * inf/arm: 1 + 0.05*arm_engine（陆军共用）
     * air: 1 + 0.05*air_engine
     * nav: 1 + 0.05*nav_engine
     */
    private double spdMul(String cat, Map<String, Integer> tech) {
        String catKey = switch (cat) {
            case "inf", "arm" -> "arm_engine";
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
    double skillBonus(Map<String, Integer> skills, String skillId) {
        if (skills == null) return 0;
        int lv = skills.getOrDefault(skillId, 0);
        if (lv <= 0) return 0;
        double rate = SKILL_RATES.getOrDefault(skillId, 0.0);
        return rate * Math.min(5, lv);
    }

    /** 首次交战回合触发，空过两个回合后再次触发；脱离射程不改变持久化的起点。 */
    boolean isSkillRoundActive(int round, int firstCombatRound) {
        return firstCombatRound > 0 && round >= firstCombatRound
                && (round - firstCombatRound) % 3 == 0;
    }

    /** 战报读取实际交火周期；将领军事、防御属性与八项战斗技能同时生效。 */
    String buildCommanderBonusLog(String sideName, TechCtx ctx, int round, boolean skillRoundActive) {
        if (ctx == null) return "";
        boolean officerActive = skillRoundActive;
        List<String> bonuses = new ArrayList<>();
        if (officerActive && ctx.commanderMil > 0) bonuses.add("军事属性 +" + ctx.commanderMil + "%攻击");
        if (officerActive && ctx.commanderDef > 0) bonuses.add("防御属性 +" + ctx.commanderDef + "%防御");
        if (skillRoundActive) {
            appendSkillBonus(bonuses, ctx.skills, "frenzy", "全军冲锋", "+", "攻击");
            appendSkillBonus(bonuses, ctx.skills, "learn", "师夷长技", "+", "同名兵种攻击（本回合，上限敌方同名兵种攻击30%）");
            appendSkillBonus(bonuses, ctx.skills, "bulwark", "坚守阵地", "+", "防御");
            if (skillBonus(ctx.skills, "bulwark") == 0) {
                appendSkillBonus(bonuses, ctx.skills, "borrow_armor", "借甲御敌", "+", "同名兵种防御（本回合，上限敌方同名兵种防御30%）");
            }
            appendSkillBonus(bonuses, ctx.skills, "counter", "绝境反击", "", "反击伤害（受击存活且可还击时）");
            appendSkillBonus(bonuses, ctx.skills, "suppress", "火力压制", "-", "敌方攻击");
            appendSkillBonus(bonuses, ctx.skills, "pierce", "破甲打击", "", "无视敌方防御");
            appendSkillBonus(bonuses, ctx.skills, "blitz", "闪电突击", "+", "速度（本回合）");
        }
        CommandBonus chief = ctx.commandBonus;
        String chiefLog = chief.officerId() == null ? "" : sideName + "司令官：" + chief.name()
                + "（常驻，技能不参与）攻击+" + bonusPercent(chief.military()) + "% / 防御+" + bonusPercent(chief.defense())
                + "% / 生命+" + bonusPercent(chief.logistics()) + "% / 机动+" + bonusPercent(chief.knowledge()) + "%\n";
        return chiefLog + sideName + "将领加成：" + (bonuses.isEmpty() ? "本回合无将领属性或技能加成生效" : String.join("；", bonuses));
    }

    private void appendSkillBonus(List<String> bonuses, Map<String, Integer> skills, String skillId,
                                  String name, String prefix, String effect) {
        double bonus = skillBonus(skills, skillId);
        if (bonus > 0) bonuses.add(name + " " + prefix + percent(bonus) + "%" + effect);
    }

    private String bonusPercent(int attribute) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, attribute) * CommandBonus.RATE * 100);
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
    public record TechCtx(Map<String, Integer> tech, Map<String, Integer> skills, int commanderMil, int commanderDef,
                          CommandBonus commandBonus) {
        public TechCtx {
            commandBonus = commandBonus != null ? commandBonus : CommandBonus.NONE;
        }
        public TechCtx(Map<String, Integer> tech, Map<String, Integer> skills, int commanderMil, int commanderDef) {
            this(tech, skills, commanderMil, commanderDef, CommandBonus.NONE);
        }
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
        return buildOrder(mine, enemy, mineCtx, foeCtx, round, false);
    }

    /** 同射程时按当回合有效速度排先手，闪电突击不能泄漏到技能间隔回合。 */
    private List<ActionEntry> buildOrder(Map<String, Integer> mine, Map<String, Integer> enemy,
                                         TechCtx mineCtx, TechCtx foeCtx, int round, boolean skillRoundActive) {
        List<ActionEntry> arr = new ArrayList<>();

        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = mineCtx != null
                    ? effSpd(e.getKey(), mineCtx, skillRoundActive)
                    : u.spd();
            arr.add(new ActionEntry(Side.MINE, e.getKey(), effectiveRange(e.getKey(), mineCtx), spd));
        }
        for (Map.Entry<String, Integer> e : enemy.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            UnitStats u = getStats(e.getKey());
            if (u == null) continue;
            double spd = foeCtx != null
                    ? effSpd(e.getKey(), foeCtx, skillRoundActive)
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
        return Math.abs(enemyX - mineX);
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

    /**
     * 计算单位本回合为机动而追逐的目标距离。
     * 所有兵种向最近存活敌军接敌，空军也不能越过任何敌方兵种。
     * 射程只限制开火，不限制前进；前排全灭后才向后续敌军推进。
     *
     * @param side 当前行动方。
     * @param unitId 行动兵种 ID。
     * @param foeArmy 敌方存活兵力。
     * @param minePos 我方坐标。
     * @param enemyPos 敌方坐标。
     * @param initialDist 战场初始宽度。
     * @return 本回合推进所依据的距离；无存活敌军时返回 {@link Integer#MAX_VALUE}。
     */
    private int advanceDistanceToLivingFoe(Side side, String unitId, Map<String, Integer> foeArmy,
                                           Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        return nearestLivingFoeDistance(side, unitId, foeArmy, minePos, enemyPos, initialDist);
    }

    /**
     * 修复历史会话已交叉的前排；优先保留现有接触线，未接触时取交叉区间中点。
     * 只将越线的存活单位收回己方，正常会话坐标不变；随后仍按同步机动和速度预算推进。
     */
    public void normalizeBattleFronts(Map<String, Integer> mine, Map<String, Integer> enemy,
                                      Map<String, Integer> minePos, Map<String, Integer> enemyPos, int initialDist) {
        Set<Integer> ownFronts = new TreeSet<>(), foeFronts = new TreeSet<>();
        mine.forEach((id, count) -> {
            if (count != null && count > 0 && getStats(id) != null) ownFronts.add(minePos.getOrDefault(id, 0));
        });
        enemy.forEach((id, count) -> {
            if (count != null && count > 0 && getStats(id) != null) foeFronts.add(enemyPos.getOrDefault(id, initialDist));
        });
        if (ownFronts.isEmpty() || foeFronts.isEmpty()) return;
        int ownFront = Collections.max(ownFronts), foeFront = Collections.min(foeFronts);
        if (ownFront <= foeFront) return;
        ownFronts.retainAll(foeFronts);
        int boundary = ownFronts.isEmpty() ? foeFront + (ownFront - foeFront) / 2 : Collections.min(ownFronts);
        mine.forEach((id, count) -> {
            if (count != null && count > 0 && getStats(id) != null)
                minePos.put(id, Math.min(minePos.getOrDefault(id, 0), boundary));
        });
        enemy.forEach((id, count) -> {
            if (count != null && count > 0 && getStats(id) != null)
                enemyPos.put(id, Math.max(enemyPos.getOrDefault(id, initialDist), boundary));
        });
    }

    /** 双方同时前进时按速度分摊接触前的剩余距离，确保所有兵种都不能越过存活敌军。 */
    private int limitAdvanceAtContact(Side side, String unitId, int step, int rawStep,
                                      Map<String, Integer> foeArmy, Map<String, Integer> minePos,
                                      Map<String, Integer> enemyPos, int initialDist,
                                      Map<String, UnitOrder> foeOrders, TechCtx foeCtx, boolean skillRoundActive) {
        for (Map.Entry<String, Integer> entry : foeArmy.entrySet()) {
            String foeId = entry.getKey();
            if (entry.getValue() == null || entry.getValue() <= 0 || getStats(foeId) == null) continue;
            int distance = getUnitDistToFoe(side, unitId, foeId, minePos, enemyPos, initialDist);
            UnitOrder order = foeOrders != null ? foeOrders.getOrDefault(foeId, foeOrders.get(UnitEquipmentEffects.base(foeId))) : null;
            CommandAction action = order != null ? order.action() : defaultAction(foeId);
            int foeStep = action == CommandAction.ADVANCE
                    ? (int) ((foeCtx != null
                    ? effSpd(foeId, foeCtx, skillRoundActive) : getStats(foeId).spd()) * 50)
                    : 0;
            int limit = distance;
            if (foeStep > 0 && rawStep > 0) {
                long speedSum = (long) rawStep + foeStep;
                long share = (long) distance * rawStep;
                limit = (int) ((share + (side == Side.MINE ? speedSum - 1 : 0)) / speedSum);
            }
            step = Math.min(step, limit);
        }
        return step;
    }

    /** 默认前进单位即使没有当前可攻击领域，也继续向最近存活敌军接近。 */
    private int nearestLivingFoeDistance(Side side, String unitId, Map<String, Integer> foeArmy,
                                         Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                         int initialDist) {
        int minDist = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : foeArmy.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || getStats(entry.getKey()) == null) continue;
            int distance = getUnitDistToFoe(side, unitId, entry.getKey(), minePos, enemyPos, initialDist);
            if (distance < minDist) minDist = distance;
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

    /** 默认索敌优先射程内的同兵种，否则选择最近的可攻击敌军。 */
    private String pickTargetInRange(String attackerId, Side side, Map<String, Integer> foeArmy,
                                     Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                     int initialDist, TechCtx ctx) {
        for (String candidate : foeArmy.keySet()) {
            if (UnitEquipmentEffects.base(candidate).equals(UnitEquipmentEffects.base(attackerId))
                    && foeArmy.getOrDefault(candidate, 0) > 0 && baseAttack(attackerId, candidate) > 0
                    && getUnitDistToFoe(side, attackerId, candidate, minePos, enemyPos, initialDist) <= effectiveRange(attackerId, ctx)) return candidate;
        }
        return pickNearestTargetInRange(attackerId, side, foeArmy, minePos, enemyPos, initialDist, ctx);
    }

    /** 无同兵种射程内目标时，以距离优先，距离相同按兵种 ID 确定目标。 */
    private String pickNearestTargetInRange(String attackerId, Side side, Map<String, Integer> foeArmy,
                                            Map<String, Integer> minePos, Map<String, Integer> enemyPos,
                                            int initialDist, TechCtx ctx) {
        int range = effectiveRange(attackerId, ctx);
        String nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : foeArmy.entrySet()) {
            String targetId = entry.getKey();
            if (entry.getValue() == null || entry.getValue() <= 0 || getStats(targetId) == null) continue;
            if (baseAttack(attackerId, targetId) <= 0) continue;
            int distance = getUnitDistToFoe(side, attackerId, targetId, minePos, enemyPos, initialDist);
            if (distance > range) continue;
            if (distance < nearestDistance || (distance == nearestDistance
                    && (nearest == null || targetId.compareTo(nearest) < 0))) {
                nearest = targetId;
                nearestDistance = distance;
            }
        }
        return nearest;
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
    public int calcProtectCap(long depotLevel, Map<String, Integer> tech) {
        BuildingDef depot = GameData.BUILDINGS.get("depot");
        int protectPer = depot != null && depot.protectPer() != null ? depot.protectPer() : 1000;
        // 每类资源分别保护同一额度；按本城仓库总等级计算，不改变储量上限。
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(Math.max(0, depotLevel) * (double) protectPer
                * (1 + .05 * com.wargame.model.constants.TechnologyRules.level(tech, "log_warehouse", 10))));
    }

    /**
     * 动作词映射 - 对应 JS Battle.verb
     */
    private String verb(String id) {
        return switch (UnitEquipmentEffects.base(id)) {
            case "infantry" -> "射击";
            case "truck" -> "碾压";
            case "armored", "recon_car" -> "扫射";
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
            // 防御力: 使用军官生效时的最大值。
            double def = effDef(unitType, t, s, 0, true, true);
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
        return calcPlunder(defenderResources, defenderWarehouseLevel, Map.of());
    }

    /** 侦查测算与实际掠夺共用仓储倍率，调用方提供守方科技快照。 */
    public Map<String, Integer> calcPlunder(Map<String, Integer> defenderResources,
                                           long defenderWarehouseLevel, Map<String, Integer> defenderTech) {
        Map<String, Integer> plunder = new LinkedHashMap<>();
        if (defenderResources == null) {
            for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                plunder.put(rk, 0);
            }
            return plunder;
        }

        int protection = calcProtectCap(defenderWarehouseLevel, defenderTech);

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
