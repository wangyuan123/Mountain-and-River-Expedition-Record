package com.wargame.service;

import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.BattleUnitIds;
import com.wargame.model.dto.BattleSkillState.Army;
import java.util.*;

/** 八项战术技能共用的状态规则；普通交火、协防、演习和侦查只负责提供各自伤害公式。 */
public final class BattleSkillEffects {
    private BattleSkillEffects() {}
    public static final Set<String> NEW_SKILLS = Set.of("ranging", "barrier", "last_stand", "execution", "smoke", "reprisal", "ricochet", "pursuit");
    public static double rate(Map<String, Integer> skills, String id) {
        double per = switch (id) {
            case "ranging" -> .08;
            case "barrier", "reprisal" -> .02;
            case "last_stand" -> .04;
            case "execution", "smoke" -> .05;
            case "ricochet" -> .03;
            case "pursuit" -> .06;
            default -> 0;
        };
        return per * Math.max(0, Math.min(5, skills == null ? 0 : skills.getOrDefault(id, 0)));
    }

    /** 初始兵力只冻结一次；同来源同兵种的配装分组共用残兵比例和追击规模分母。 */
    public static void begin(Army state, int round, Map<String, Integer> alive,
                             Map<String, Integer> initial, Map<String, String> sources) {
        if (state.getInitial().isEmpty()) {
            state.getInitial().putAll(initial);
            state.getSources().putAll(sources);
        }
        if (state.getRound() == round) return;
        state.setRound(round);
        state.setOpening(new LinkedHashMap<>(alive));
        state.getEvents().clear();
        state.getUnits().values().forEach(unit -> {
            unit.setBarrier(0);
            unit.getReceived().keySet().removeIf(r -> r < round - 2);
        });
    }

    private static String source(Army state, String id) { return state.getSources().getOrDefault(id, "main"); }
    public static boolean sameTarget(Army state, String first, String second) {
        return source(state, first).equals(source(state, second))
                && BattleUnitIds.base(first).equals(BattleUnitIds.base(second));
    }
    private static int count(Army state, Map<String, Integer> army, String id) {
        return army.entrySet().stream().filter(e -> source(state, id).equals(source(state, e.getKey()))
                && BattleUnitIds.base(id).equals(BattleUnitIds.base(e.getKey())))
                .mapToInt(e -> Math.max(0, e.getValue())).sum();
    }
    public static boolean troop(String id) { return UnitDef.UNITS.containsKey(BattleUnitIds.base(id)); }

    /** 背水只放大自身攻击，回合内减员不会临时跨过阈值；学习所得攻击仍独立追加。 */
    public static double ownAttack(Army state, String id, Map<String, Integer> skills) {
        int initial = count(state, state.getInitial(), id);
        double bonus = troop(id) && initial > 0 && count(state, state.getOpening(), id) < initial * .5
                ? rate(skills, "last_stand") : 0;
        if (bonus > 0) effect(state, "last_stand", id, "", bonus);
        return 1 + bonus;
    }

    /** 烟幕与追击仅用于机动，先手和开局距离继续使用原速度规则。 */
    public static double movement(Army state, String id, Map<String, Integer> skills) {
        var unit = state.unit(id);
        return (1 + (unit.getPursuitRound() == state.getRound() ? rate(skills, "pursuit") : 0))
                * (1 - (unit.getSmokeRound() == state.getRound() ? unit.getSmokeRate() : 0));
    }

    /** 同一兵种每回合只记录一次主目标；第三轮校射只增强该主目标，不给余伤重新计次。 */
    public static void aim(Army own, String id, Army foe, String target, Map<String, Integer> skills) {
        var unit = own.unit(id);
        if (unit.getAttackRound() == own.getRound()) return;
        unit.setAttackRound(own.getRound());
        String focus = source(foe, target) + ":" + BattleUnitIds.base(target);
        int stack = unit.getFocusRound() == own.getRound() - 1 && focus.equals(unit.getFocus())
                ? unit.getFocusCount() + 1 : 1;
        unit.setFocus(focus); unit.setFocusRound(own.getRound());
        unit.setFocusCount(stack == 3 ? 0 : stack);
        unit.setRangingRate(stack == 3 && troop(target) ? rate(skills, "ranging") : 0);
        unit.setPursuitAttackRate(unit.getPursuitRound() == own.getRound() ? rate(skills, "pursuit") / 2 : 0);
        if (unit.getRangingRate() > 0) effect(own, "ranging", id, target, unit.getRangingRate());
        if (unit.getPursuitAttackRate() > 0) effect(own, "pursuit", id, target, unit.getPursuitAttackRate());
    }

    /** 收割按本次攻击前存活兵力判断；校射与追击只增强本回合首次主动攻击。 */
    public static double outgoing(Army own, String id, Army foe, String target,
            Map<String, Integer> foeAlive, Map<String, Integer> skills, boolean primary, double normal) {
        double result = normal;
        int initial = count(foe, foe.getInitial(), target);
        if (troop(target) && initial > 0 && count(foe, foeAlive, target) <= initial * .3) {
            double bonus = rate(skills, "execution");
            result *= 1 + bonus;
            if (bonus > 0) effect(own, "execution", id, target, bonus);
        }
        if (primary) {
            var unit = own.unit(id);
            result *= (1 + unit.getRangingRate()) * (1 + unit.getPursuitAttackRate());
        }
        return result;
    }

    /** 蓄势读取此前两回合的有效承伤；同回合、屏障和溢出伤害均不计入，不产生递归。 */
    public static double reprisal(Army own, String id, String target, Map<String, Integer> skills,
                                  boolean active, double normal) {
        var unit = own.unit(id);
        if (!active || unit.getRevengeRound() == own.getRound() || rate(skills, "reprisal") <= 0) return 0;
        unit.setRevengeRound(own.getRound());
        double received = unit.getReceived().entrySet().stream().filter(e -> e.getKey() < own.getRound()
                && e.getKey() >= own.getRound() - 2).mapToDouble(Map.Entry::getValue).sum();
        double extra = Math.min(normal * .4, received * rate(skills, "reprisal"));
        unit.getReceived().keySet().removeIf(r -> r < own.getRound());
        if (extra > 0) effect(own, "reprisal", id, target, extra);
        return extra;
    }

    /** 屏障按本回合开始兵力生成一次；首次接战在伤害前开启也能保护第一发。 */
    public static double shield(Army own, String id, Map<String, Integer> skills, boolean active, double hp) {
        var unit = own.unit(id);
        if (unit.getBarrierRound() != own.getRound()) {
            unit.setBarrierRound(own.getRound());
            unit.setBarrier(active && troop(id) ? own.getOpening().getOrDefault(id, 0) * hp * rate(skills, "barrier") : 0);
            if (unit.getBarrier() > 0) effect(own, "barrier", id, "", unit.getBarrier());
        }
        return unit.getBarrier();
    }

    /** 返回屏障之后的伤害，并仅记录尚存部队能承受的实际生命损失。 */
    public static double receive(Army own, String id, Map<String, Integer> skills, boolean active,
                                  double hp, int alive, double damage) {
        double absorbed = Math.min(Math.max(0, damage), shield(own, id, skills, active, hp));
        own.unit(id).setBarrier(own.unit(id).getBarrier() - absorbed);
        double net = Math.max(0, damage - absorbed);
        if (troop(id)) own.unit(id).getReceived().merge(own.getRound(), Math.min(net, hp * alive), Double::sum);
        if (absorbed > 0) effect(own, "barrier", id, "", -absorbed);
        return net;
    }

    public static void smoke(Army own, String id, Army foe, String target, Map<String, Integer> skills, boolean active) {
        double rate = rate(skills, "smoke");
        if (!active || rate <= 0 || !troop(target)) return;
        var unit = foe.unit(target);
        unit.setSmokeRate(unit.getSmokeRound() == own.getRound() + 1 ? Math.max(unit.getSmokeRate(), rate) : rate);
        unit.setSmokeRound(own.getRound() + 1);
        effect(own, "smoke", id, target, rate);
    }

    /** 歼灭规模按敌方所属编队开战人口计算，同批贡献者各自取得一次下一回合追击。 */
    public static void defeated(Army own, String id, Army foe, String target, Map<String, Integer> skills) {
        if (!troop(target) || rate(skills, "pursuit") <= 0) return;
        double total = foe.getInitial().entrySet().stream().filter(e -> source(foe, target).equals(source(foe, e.getKey()))
                && troop(e.getKey())).mapToDouble(e -> e.getValue() * population(e.getKey())).sum();
        double targetPop = count(foe, foe.getInitial(), target) * population(target);
        if (total > 0 && targetPop >= total * .05) {
            own.unit(id).setPursuitRound(own.getRound() + 1);
            effect(own, "pursuit", id, target, rate(skills, "pursuit"));
        }
    }
    private static int population(String id) { return UnitDef.UNITS.get(BattleUnitIds.base(id)).pop(); }

    public static void effect(Army state, String skill, String unit, String target, double value) {
        Map<String, Object> event = new LinkedHashMap<>(Map.of("skill", skill, "unit", unit, "target", target, "value", value));
        if (!state.getEvents().contains(event)) state.getEvents().add(event);
    }

    /** 事件是实际触发事实，日志与前端复盘都不把“窗口开启”误当作已命中。 */
    public static String log(Army state, String side) {
        StringBuilder out = new StringBuilder();
        for (var event : state.getEvents()) {
            var skill = OfficerSkillDef.getSkill(event.get("skill").toString());
            out.append(side).append("技能触发：").append(skill.name()).append("（")
                    .append(BattleUnitIds.base(event.get("unit").toString())).append("） ")
                    .append(String.format(Locale.ROOT, "%.2f", ((Number) event.get("value")).doubleValue())).append("\n");
        }
        return out.toString();
    }
}
