package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定预算包含一次性装备投入，记录实际战斗结果，不用胜负断言锁死平衡初值。 */
class UnitEquipmentBalanceTest {
    private record Force(String unit, List<String> gear) {}
    private Force force(String unit, String... items) { return new Force(unit, List.of(items)); }
    private int cost(Map<String, Integer> resources) { return resources.values().stream().mapToInt(Integer::intValue).sum(); }
    private int gearCost(Force force) { return force.gear().stream().map(UnitEquipmentDef.ITEMS::get).mapToInt(d -> cost(d.cost())).sum(); }
    private Map<String, Integer> tech(int level, Force force) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : List.of("attack_tech", "defense_tech", "cmd_hp", "weapon_range", "arm_engine", "air_engine", "nav_engine")) out.put(key, level);
        Map<String, String> slots = new LinkedHashMap<>(); force.gear().forEach(key -> slots.put(UnitEquipmentDef.ITEMS.get(key).slot(), key));
        out.putAll(UnitEquipmentEffects.snapshot(Map.of(force.unit(), slots))); return out;
    }
    @Test void lowMediumHighBudgetsAndTechnologyProduceBoundedReproducibleBattles() throws Exception {
        Force infantry = force("infantry"), coordinated = force("infantry", "ue_infantry_helmet", "ue_infantry_lmg", "ue_infantry_radio");
        Force protectedInfantry = force("infantry", "ue_infantry_vest", "ue_infantry_lmg", "ue_infantry_radio");
        Force rocket = force("rocket"), tank = force("ltank");
        Force linedTank = force("ltank", "ue_ltank_armor", "ue_ltank_gun", "ue_ltank_liner");
        List<List<Force>> scenarios = List.of(List.of(infantry, infantry), List.of(coordinated, infantry), List.of(protectedInfantry, infantry), List.of(infantry, coordinated), List.of(rocket, tank), List.of(rocket, linedTank));
        StringBuilder csv = new StringBuilder("budget,tech,attacker,attackerGear,attackerInitial,defender,defenderGear,defenderInitial,attackerWon,attackerAlive,defenderAlive,rounds\n");
        int cases = 0;
        for (int budget : List.of(10000, 50000, 500000)) for (int level : List.of(0, 5, 10)) for (List<Force> scenario : scenarios) {
            Force a = scenario.get(0), d = scenario.get(1);
            int countA = Math.max(0, (budget - gearCost(a)) / cost(UnitDef.UNITS.get(a.unit()).cost()));
            int countD = Math.max(0, (budget - gearCost(d)) / cost(UnitDef.UNITS.get(d.unit()).cost()));
            assertTrue(countA > 0 && countD > 0);
            assertTrue(countA * cost(UnitDef.UNITS.get(a.unit()).cost()) + gearCost(a) <= budget);
            assertTrue(countD * cost(UnitDef.UNITS.get(d.unit()).cost()) + gearCost(d) <= budget);
            BattleResult result = new BattleService(42).startWorldDispatch(Map.of(a.unit(), countA), Map.of(d.unit(), countD), Map.of(), tech(level, a), tech(level, d), Map.of(), Map.of(), 0, 0, "conquer", Map.of(), 0, true);
            int aliveA = result.getSurvivorAttacker().getOrDefault(a.unit(), 0), aliveD = result.getSurvivorDefender().getOrDefault(d.unit(), 0);
            assertTrue(aliveA >= 0 && aliveA <= countA); assertTrue(aliveD >= 0 && aliveD <= countD);
            int rounds = result.getReport().split("-- 第", -1).length - 1;
            assertTrue(rounds > 0 && rounds <= BattleService.CONQUER_MAX_ROUND,
                    "budget=" + budget + ", tech=" + level + ", scenario=" + scenario + ", rounds=" + rounds);
            csv.append(budget).append(',').append(level).append(',').append(a.unit()).append(',').append(String.join("|", a.gear())).append(',').append(countA).append(',').append(d.unit()).append(',').append(String.join("|", d.gear())).append(',').append(countD).append(',').append(result.isWin()).append(',').append(aliveA).append(',').append(aliveD).append(',').append(rounds).append('\n'); cases++;
        }
        assertEquals(54, cases);
        String destination = System.getProperty("unit-equipment.report");
        if (destination != null) { Path path = Path.of(destination); Files.createDirectories(path.getParent()); Files.writeString(path, csv); }
    }
}
