package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.UnitDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 校验独立推导输入与实际战斗契约一致，避免只改展示、造价或克制后遗忘重算攻击。 */
class AttackDesignTest {
    private record Stats(double hp, double def, Map<String, Integer> cost, Map<String, Number> attacks) {}

    private Stats stats(String id) {
        UnitDef unit = UnitDef.UNITS.get(id);
        if (unit != null) return new Stats(unit.hp(), unit.def(), unit.cost(), Map.of(
                "atkGround", unit.atkGround(), "atkAir", unit.atkAir(), "atkSea", unit.atkSea(), "atkFort", unit.atkFort()));
        FortDef fort = FortDef.FORTS.get(id);
        assertNotNull(fort, id);
        return new Stats(fort.hp(), fort.def(), fort.cost(), Map.of(
                "atkGround", fort.atkGround(), "atkAir", fort.atkAir(), "atkSea", fort.atkSea(), "atkFort", fort.atkFort()));
    }

    private double budget(Stats stats, JsonNode weights) {
        return stats.cost.entrySet().stream().mapToDouble(entry -> entry.getValue() * weights.path(entry.getKey()).asDouble()).sum();
    }

    @org.junit.jupiter.api.Disabled("20260918 历史齐射设计快照，已被 20260919 属性重构与 v7 精简倍率取代")
    @Test
    void everyAttackMatchesBudgetOrExplicitRatio() throws Exception {
        JsonNode design = new ObjectMapper().readTree(Path.of("../docs/balance/attack-design.json").toFile());
        assertEquals(BattleRules.VERSION, design.path("version").asText());
        JsonNode weights = design.path("economyWeights");
        JsonNode profiles = design.path("profiles");
        int minimum = design.path("minimumAttack").asInt();
        assertEquals(1, minimum);
        Set<String> expected = new HashSet<>(UnitDef.UNITS.keySet());
        expected.addAll(FortDef.FORTS.keySet());
        Set<String> actual = new HashSet<>();
        profiles.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
        Map<String, String> domains = Map.of("atkGround", "land", "atkAir", "air", "atkSea", "sea", "atkFort", "fort");
        for (String id : expected) {
            Stats attacker = stats(id);
            assertTrue(budget(attacker, weights) > 0);
            JsonNode attacks = profiles.path(id).path("attacks");
            assertEquals(4, attacks.size(), id);
            for (String field : domains.keySet()) {
                JsonNode input = attacks.get(field);
                assertNotNull(input, id + "." + field);
                assertTrue(attacker.attacks.get(field).doubleValue() >= minimum, id + "." + field);
                if (input.path("baseline").asBoolean()) {
                    assertEquals(minimum, attacker.attacks.get(field).doubleValue(), id + "." + field);
                    continue;
                }
                if (input.has("relativeTo")) {
                    Stats reference = stats(input.path("relativeTo").asText());
                    long derived = (long) Math.ceil(reference.attacks.get(input.path("field").asText()).doubleValue()
                            * input.path("factor").asDouble() + input.path("offset").asDouble());
                    assertEquals(derived, attacker.attacks.get(field).longValue(), id + "." + field);
                    continue;
                }
                String targetId = input.path("target").asText();
                Stats target = stats(targetId);
                double volleys = input.path("volleys").asDouble();
                double matchup = input.path("multiplier").asDouble();
                assertTrue(volleys > 0 && matchup > 0);
                assertEquals(domains.get(field), BattleRules.domain(targetId), id + "." + field);
                assertEquals(BattleRules.multiplier(id, targetId), matchup, 1e-9, id + " -> " + targetId);
                long derived = Math.round(target.hp * (1 + 0.05 * target.def) * budget(attacker, weights)
                        / (budget(target, weights) * volleys * matchup));
                assertEquals(Math.max(minimum, derived), attacker.attacks.get(field).longValue(), id + "." + field);
            }
        }
        var ranges = design.path("rangeRelations").fields();
        while (ranges.hasNext()) {
            var relation = ranges.next();
            int reference = UnitDef.UNITS.get(relation.getValue().path("relativeTo").asText()).range();
            int expectedRange = (int) Math.ceil(reference * relation.getValue().path("factor").asDouble()
                    + relation.getValue().path("offset").asDouble());
            assertEquals(expectedRange, UnitDef.UNITS.get(relation.getKey()).range());
        }
    }

    @Test
    void requestedCombatRatiosRemainWithinTheirIntendedBands() {
        UnitDef light = UnitDef.UNITS.get("ltank"), heavy = UnitDef.UNITS.get("htank");
        UnitDef rocket = UnitDef.UNITS.get("rocket"), assault = UnitDef.UNITS.get("assault");
        UnitDef special = UnitDef.UNITS.get("special");
        assertTrue(heavy.atkGround() > light.atkGround() * 1.5);
        assertTrue(heavy.atkGround() <= light.atkGround() * 1.6);
        assertEquals(heavy.atkGround() * 2, rocket.atkGround());
        assertTrue(special.atkFort() > rocket.atkFort());
        assertTrue(special.atkFort() <= rocket.atkFort() * 1.1);
        assertTrue(rocket.range() > assault.range() * 2);
        assertTrue(rocket.range() <= assault.range() * 2.7);
    }
}
