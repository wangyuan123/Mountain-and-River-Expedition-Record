package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import java.util.*;

/** 独立侦查拦截保留原公式；配置组分别承伤，不用平均防御替代真实耐久。 */
public final class UnitEquipmentRecon {
    public static final String INITIATIVE_RULES_VERSION = "recon-initiative-v6-20261019";
    private UnitEquipmentRecon() {}
    public record Result(int ownAlive, Map<String, Integer> enemyAlive, double ownPower, double enemyPower,
                         int attackerInitiative, int defenderInitiative, List<String> log) {}

    /** 侦查只用对应领域攻击；将领周期加成叠加在原配装、司令官攻击上，学习值独立追加。 */
    private static double attack(BattleService battle, UnitDef u, String id, String target,
                                 BattleService.TechCtx own, BattleService.TechCtx foe, boolean active,
                                 Set<String> sources, boolean counter) {
        double base = "air".equals(u.branch()) ? u.atkAir() : u.atkGround();
        double result = base * UnitEquipmentEffects.attack(own.tech(), id, target) * own.commandBonus().attackMul();
        if (active) {
            result *= (1 + own.commanderMil() / 100.0) * (1 + battle.skillBonus(own.skills(), "frenzy"));
            result *= 1 - battle.skillBonus(foe.skills(), "suppress");
            result += battle.learningBonus(id, target, own, foe, true, sources, counter);
        }
        return result;
    }

    /** 防御属性、坚守/借甲、破甲只影响原式的防御项，生命不随将领周期放大。 */
    private static double durability(BattleService battle, UnitDef u, String id, BattleService.TechCtx own,
                                     BattleService.TechCtx foe, boolean active, Set<String> sources) {
        double defense = battle.effDef(id, own.tech(), own.skills(), own.commanderDef(), active, active, active,
                foe, sources, own.commandBonus());
        if (active) defense *= 1 - battle.skillBonus(foe.skills(), "pierce");
        return defense + u.hp() * UnitEquipmentEffects.mul(own.tech(), id, "hp") * own.commandBonus().hpMul();
    }

    /** +1击杀及按伤害扣除余量沿用原侦查公式；返回受击配置组供反击技能读取存活兵力。 */
    private static Set<String> damage(BattleService battle, Map<String, Integer> alive, UnitDef unit, double power,
                                       BattleService.TechCtx own, BattleService.TechCtx foe, boolean active,
                                       Set<String> sources, boolean extraKill) {
        Set<String> hit = new LinkedHashSet<>();
        for (String id : alive.keySet()) {
            int n = alive.get(id); if (n <= 0) continue;
            double hp = durability(battle, unit, id, own, foe, active, sources);
            int kills = Math.min(n, (int) Math.floor(power / hp) + (extraKill ? 1 : 0));
            alive.put(id, n - kills);
            hit.add(id);
            if (kills < n) break;
            power = Math.max(0, power - (kills - (extraKill ? 1 : 0)) * hp); extraKill = false;
            if (power <= 0) break;
        }
        return hit;
    }

    /** 无随队将领的侦查入口，双方侦察等级分别常驻，守方雷达等级仅在首回合提供每级2点预警。 */
    public static Result resolve(String unit, int own, Map<String, Integer> defense, Map<String, Integer> aGear,
                                 Map<String, Integer> dGear, CommandBonus aChief, CommandBonus dChief,
                                 int attackerReconLevel, int defenderReconLevel, int defenderRadarLevel) {
        return resolve(unit, own, defense, new BattleService(),
                new BattleService.TechCtx(aGear, Map.of(), 0, 0, aChief),
                new BattleService.TechCtx(dGear, Map.of(), 0, 0, dChief),
                attackerReconLevel, defenderReconLevel, defenderRadarLevel);
    }

    /**
     * 侦查统一按先手和存活兵力逐回合交火，保留伤害余量和10回合上限；雷达仅首回合增加先手。
     * 首回合即交火，将领属性与技能在1/4/7/10回合生效；仅复用加成计算，不进入普通战斗引擎。
     */
    public static Result resolve(String unit, int own, Map<String, Integer> defense, BattleService battle,
                                 BattleService.TechCtx attacker, BattleService.TechCtx defender,
                                 int attackerReconLevel, int defenderReconLevel, int defenderRadarLevel) {
        BattleService.TechCtx ac = reconContext(attacker), dc = reconContext(defender);
        UnitDef u = UnitDef.UNITS.get(unit);
        Map<String, Integer> alive = new LinkedHashMap<>();
        defense.forEach((id, n) -> { if (UnitEquipmentEffects.base(id).equals(unit) && n > 0) alive.put(id, n); });
        Set<String> aSources = own > 0 ? Set.of(unit) : Set.of(), dSources = livingTypes(alive);
        // 战力读取首次交火的有效攻击和耐久，仅供报告展示，实际战损由逐回合交火决定。
        double ownAttack = attack(battle, u, unit, unit, ac, dc, true, dSources, false);
        double ownDurability = durability(battle, u, unit, ac, dc, true, dSources);
        double ownPower = own * (ownAttack + ownDurability);
        double enemyPower = alive.entrySet().stream().mapToDouble(e -> e.getValue() *
                (attack(battle, u, e.getKey(), unit, dc, ac, true, aSources, false)
                        + durability(battle, u, e.getKey(), dc, ac, true, aSources))).sum();
        // 雷达仅为守方首次交火提供每级2点先手，10级封顶20点，不随将领技能窗口重触发。
        int radarBonus = 2 * Math.max(0, Math.min(10, defenderRadarLevel));
        attackerReconLevel = Math.max(0, Math.min(10, attackerReconLevel)) * 5;
        defenderReconLevel = Math.max(0, Math.min(10, defenderReconLevel)) * 5;
        int aInitiative = initiative(battle, u, unit, ac, true, attackerReconLevel);
        Map<String, Integer> initiative = initiatives(battle, u, alive, dc, true, defenderReconLevel + radarBonus);
        int dInitiative = initiative.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        boolean officerCombat = hasOfficerEffects(battle, ac) || hasOfficerEffects(battle, dc);
        List<String> log = new ArrayList<>();
        int ownAlive = own;
        for (int round = 1; round <= 10 && ownAlive > 0 && total(alive) > 0; round++) {
            boolean active = battle.isSkillRoundActive(round, 1);
            // 同名参照在回合开始固定；周期外所有属性和技能关闭，常驻配装、司令官仍沿用原计算。
            Set<String> enemySources = livingTypes(alive);
            double currentAttack = attack(battle, u, unit, unit, ac, dc, active, enemySources, false);
            double currentDurability = durability(battle, u, unit, ac, dc, active, enemySources);
            int aFirst = initiative(battle, u, unit, ac, active, attackerReconLevel);
            Map<String, Integer> dFirst = initiatives(battle, u, alive, dc, active,
                    defenderReconLevel + (round == 1 ? radarBonus : 0));
            List<String> actors = new ArrayList<>(dFirst.keySet()); actors.add("@attacker");
            // 同值时按交火回合奇偶轮换阵营，守方配装组之间仍保持稳定顺序；阵亡组不参与先手。
            boolean attackerWinsTie = round % 2 == 1;
            actors.sort(Comparator.<String>comparingInt(id -> "@attacker".equals(id) ? aFirst : dFirst.get(id))
                    .reversed().thenComparingInt(id -> "@attacker".equals(id) == attackerWinsTie ? 0 : 1)
                    .thenComparing(Comparator.naturalOrder()));
            boolean enemyExtra = true;
            List<String> counters = new ArrayList<>();
            // 原侦查按总伤害传递余量，不改为普通战斗的攻击次数、射程、机动或逐目标行动结算。
            for (String actor : actors) {
                if (ownAlive <= 0) break;
                if ("@attacker".equals(actor)) {
                    Set<String> hit = damage(battle, alive, u, ownAlive * currentAttack, dc, ac, active, aSources, true);
                    double rate = active ? battle.skillBonus(dc.skills(), "counter") : 0;
                    // 反击是额外技能伤害，仍除以防御+生命；只有受击存活配置组参与，无+1击杀且不递归。
                    for (String id : hit) {
                        if (rate <= 0 || alive.get(id) <= 0 || ownAlive <= 0) continue;
                        double power = alive.get(id) * attack(battle, u, id, unit, dc, ac, active, aSources, true)
                                * rate * UnitEquipmentEffects.damage(ac.tech(), unit, id);
                        int lost = Math.min(ownAlive, (int) Math.floor(power / currentDurability));
                        ownAlive -= lost;
                        counters.add("敌方绝境反击：击毁" + lost + "，剩余兵力" + alive.get(id) + "参与反击");
                    }
                } else if (alive.get(actor) > 0) {
                    double power = alive.get(actor) * attack(battle, u, actor, unit, dc, ac, active, aSources, false)
                            * UnitEquipmentEffects.damage(ac.tech(), unit, actor);
                    ownAlive -= Math.min(ownAlive, (int) Math.floor(power / currentDurability) + (enemyExtra ? 1 : 0)); enemyExtra = false;
                    double rate = active ? battle.skillBonus(ac.skills(), "counter") : 0;
                    if (rate > 0 && ownAlive > 0) {
                        int prior = alive.get(actor);
                        Map<String, Integer> hit = new LinkedHashMap<>(Map.of(actor, prior));
                        double counterPower = ownAlive * attack(battle, u, unit, unit, ac, dc, active, enemySources, true) * rate;
                        damage(battle, hit, u, counterPower, dc, ac, active, aSources, false);
                        alive.put(actor, hit.get(actor));
                        counters.add("我方绝境反击：击毁" + (prior - hit.get(actor)) + "，剩余兵力" + ownAlive + "参与反击");
                    }
                }
            }
            String entry = "第" + round + "回合：我方剩余" + ownAlive + "，敌方剩余" + total(alive);
            int dMax = dFirst.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            entry += "\n本回合先手：" + ("@attacker".equals(actors.get(0)) ? "我方" : "敌方")
                    + "（我方" + aFirst + " / 敌方" + dMax + "）";
            if (round == 1 && radarBonus > 0) entry += "\n敌方雷达预警：先手+" + radarBonus + "（仅首回合）";
            if (officerCombat) {
                entry += "\n" + battle.buildCommanderBonusLog("我方", ac, round, active)
                        + "\n" + battle.buildCommanderBonusLog("敌方", dc, round, active);
                if (!counters.isEmpty()) entry += "\n" + String.join("\n", counters);
            }
            log.add(entry);
        }
        return new Result(ownAlive, alive, ownPower, enemyPower, aInitiative, dInitiative, log);
    }

    /** 普通攻防生命科技不进入侦查，只保留原常驻配装和实际将领快照。 */
    private static BattleService.TechCtx reconContext(BattleService.TechCtx ctx) {
        return new BattleService.TechCtx(UnitEquipmentEffects.only(ctx.tech()),
                ctx.skills() == null ? Map.of() : ctx.skills(), ctx.commanderMil(), ctx.commanderDef(), ctx.commandBonus());
    }

    private static boolean hasOfficerEffects(BattleService battle, BattleService.TechCtx ctx) {
        return ctx.commanderMil() > 0 || ctx.commanderDef() > 0
                || List.of("frenzy", "bulwark", "blitz", "suppress", "pierce", "counter", "learn", "borrow_armor")
                .stream().anyMatch(skill -> battle.skillBonus(ctx.skills(), skill) > 0);
    }

    private static int total(Map<String, Integer> army) { return army.values().stream().mapToInt(Integer::intValue).sum(); }

    private static Set<String> livingTypes(Map<String, Integer> army) {
        Set<String> ids = new HashSet<>();
        army.forEach((id, n) -> { if (n > 0) ids.add(UnitEquipmentEffects.base(id)); });
        return ids;
    }

    private static int initiative(BattleService battle, UnitDef u, String id, BattleService.TechCtx ctx,
                                  boolean active, int offset) {
        return (int) Math.round(u.spd() * UnitEquipmentEffects.speed(ctx.tech(), id, true) * ctx.commandBonus().speedMul()
                * (1 + (active ? battle.skillBonus(ctx.skills(), "blitz") : 0))) + offset;
    }

    private static Map<String, Integer> initiatives(BattleService battle, UnitDef u, Map<String, Integer> army,
                                                    BattleService.TechCtx ctx, boolean active, int offset) {
        Map<String, Integer> result = new LinkedHashMap<>();
        army.forEach((id, count) -> { if (count > 0) result.put(id, initiative(battle, u, id, ctx, active, offset)); });
        return result;
    }
}
