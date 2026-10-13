package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import java.util.*;

/** 独立侦查拦截保留原公式；配置组分别承伤，不用平均防御替代真实耐久。 */
public final class UnitEquipmentRecon {
    private UnitEquipmentRecon() {}
    public record Result(int ownAlive, Map<String, Integer> enemyAlive, double ownPower, double enemyPower,
                         int attackerInitiative, int defenderInitiative, List<String> log) {}
    private static double attack(UnitDef u, String id, String target, Map<String, Integer> gear, CommandBonus chief) {
        double base = "air".equals(u.branch()) ? u.atkAir() : u.atkGround();
        return base * UnitEquipmentEffects.attack(gear, id, target) * chief.attackMul();
    }
    private static double durability(UnitDef u, String id, Map<String, Integer> gear, CommandBonus chief) {
        return u.def() * UnitEquipmentEffects.mul(gear, id, "defense") * chief.defenseMul()
                + u.hp() * UnitEquipmentEffects.mul(gear, id, "hp") * chief.hpMul();
    }
    /** +1 击杀沿用原侦查模型，每一方每回合只获得一次；余伤按下一配置组耐久重新计算。 */
    private static void damage(Map<String, Integer> alive, UnitDef unit, double power, Map<String, Integer> gear,
                               CommandBonus chief, boolean extraKill) {
        for (String id : alive.keySet()) {
            int n = alive.get(id); if (n <= 0) continue;
            double hp = durability(unit, id, gear, chief);
            int kills = Math.min(n, (int) Math.floor(power / hp) + (extraKill ? 1 : 0));
            alive.put(id, n - kills);
            if (kills < n) break;
            power = Math.max(0, power - (kills - (extraKill ? 1 : 0)) * hp); extraKill = false;
            if (power <= 0) break;
        }
    }
    public static Result resolve(String unit, int own, Map<String, Integer> defense, Map<String, Integer> aGear,
                                 Map<String, Integer> dGear, CommandBonus aChief, CommandBonus dChief, int warning) {
        UnitDef u = UnitDef.UNITS.get(unit);
        Map<String, Integer> alive = new LinkedHashMap<>();
        defense.forEach((id, n) -> { if (UnitEquipmentEffects.base(id).equals(unit) && n > 0) alive.put(id, n); });
        double ownAttack = attack(u, unit, unit, aGear, aChief), ownDurability = durability(u, unit, aGear, aChief);
        double ownPower = own * (ownAttack + ownDurability);
        double enemyPower = alive.entrySet().stream().mapToDouble(e -> e.getValue() * (attack(u, e.getKey(), unit, dGear, dChief) + durability(u, e.getKey(), dGear, dChief))).sum();
        int aInitiative = (int) Math.round(u.spd() * UnitEquipmentEffects.speed(aGear, unit, true) * aChief.speedMul()) + 2;
        Map<String, Integer> initiative = new LinkedHashMap<>();
        alive.keySet().forEach(id -> initiative.put(id, (int) Math.round(u.spd() * UnitEquipmentEffects.speed(dGear, id, true) * dChief.speedMul()) + warning));
        int dInitiative = initiative.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> log = new ArrayList<>();
        int ownAlive = own;
        if (enemyPower > 0 && ownPower / enemyPower < .3) { damage(alive, u, ownPower, dGear, dChief, false); ownAlive = 0; }
        else for (int round = 1; round <= 10 && ownAlive > 0 && alive.values().stream().anyMatch(n -> n > 0); round++) {
            List<String> actors = new ArrayList<>(alive.keySet()); actors.add("@attacker");
            actors.sort(Comparator.<String>comparingInt(id -> "@attacker".equals(id) ? aInitiative : initiative.get(id)).reversed().thenComparing(id -> "@attacker".equals(id) ? "" : id));
            boolean enemyExtra = true;
            // 分组不会增加守方的免费击杀；各组先手仍读取该派兵人的装备速度。
            for (String actor : actors) {
                if (ownAlive <= 0) break;
                if ("@attacker".equals(actor)) damage(alive, u, ownAlive * ownAttack, dGear, dChief, true);
                else if (alive.get(actor) > 0) {
                    double power = alive.get(actor) * attack(u, actor, unit, dGear, dChief) * UnitEquipmentEffects.damage(aGear, unit, actor);
                    ownAlive -= Math.min(ownAlive, (int) Math.floor(power / ownDurability) + (enemyExtra ? 1 : 0)); enemyExtra = false;
                }
            }
            log.add("第" + round + "回合：我方剩余" + ownAlive + "，敌方剩余" + alive.values().stream().mapToInt(Integer::intValue).sum());
        }
        return new Result(ownAlive, alive, ownPower, enemyPower, aInitiative, dInitiative, log);
    }
}
