package com.wargame;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实单回合机动和交火复现混编前排阻挡造成的微小间距。 */
class BattleContactMovementTest {
    private static final BattleService.UnitOrder HOLD = new BattleService.UnitOrder(BattleService.CommandAction.HOLD);

    @Test
    void advancingHeavyTanksCloseResidualGapsAndReceiveContactDamageOnEitherSide() {
        for (int gap : new int[]{1, 2}) {
            for (boolean mirror : new boolean[]{false, true}) {
                Map<String, Integer> mixed = Map.of("destroyer", 100_000, "htank", 100_000);
                Map<String, Integer> mixedPositions = Map.of("destroyer", 100, "htank", mirror ? 100 - gap : 100 + gap);
                BattleRoundState state = round(
                        mirror ? mixed : Map.of("htank", 100_000),
                        mirror ? Map.of("htank", 100_000) : mixed,
                        mirror ? mixedPositions : Map.of("htank", 100),
                        mirror ? Map.of("htank", 100) : mixedPositions, Map.of(), Map.of());

                assertEquals(100, state.attackerPositions().get("htank"), state.log());
                assertEquals(100, state.defenderPositions().get("htank"), state.log());
                for (String prefix : new String[]{"我方", "敌方"}) {
                    String line = heavyTankLine(state, prefix);
                    assertTrue(line.contains("贴脸×1.5"), line);
                    assertTrue(line.contains("目标距离0"), line);
                }
            }
        }
    }

    @Test
    void holdingRearTargetKeepsItsRealGapAndDoesNotReceiveContactMultiplier() {
        for (int gap : new int[]{1, 2}) {
            BattleRoundState state = round(Map.of("htank", 100_000),
                    Map.of("destroyer", 100_000, "htank", 100_000),
                    Map.of("htank", 100), Map.of("destroyer", 100, "htank", 100 + gap),
                    Map.of(), Map.of("htank", HOLD));

            assertEquals(100 + gap, state.defenderPositions().get("htank"), state.log());
            String line = heavyTankLine(state, "我方");
            assertTrue(line.contains("已接触前排"), line);
            assertFalse(line.contains("已贴脸"), line);
            assertFalse(line.contains("贴脸×"), line);
            assertTrue(line.contains("目标距离" + gap), line);
        }
    }

    @Test
    void mixedAdvancesReachTheSameFrontWithinTheFirstContactRoundRegardlessOfMapOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            Map<String, Integer> army = new LinkedHashMap<>();
            army.put(reverse ? "htank" : "destroyer", 100_000);
            army.put(reverse ? "destroyer" : "htank", 100_000);
            BattleRoundState state = round(Map.of("htank", 100_000), army,
                    Map.of("htank", 0), Map.of("destroyer", 100, "htank", 101), Map.of(), Map.of());

            int contact = state.attackerPositions().get("htank");
            assertEquals(contact, state.defenderPositions().get("destroyer"), state.log());
            assertEquals(contact, state.defenderPositions().get("htank"), state.log());
        }
    }

    @Test
    void contactCompletionNeverPullsSlowHoldingRetreatingOrImmobileUnitsToTheFront() {
        Map<String, Integer> a = Map.of("htank", 100_000, "infantry", 100_000, "bunker", 100_000,
                "truck", 100_000, "scout", 100_000);
        BattleRoundState state = round(a, Map.of("htank", 100_000),
                Map.of("htank", 600, "infantry", 0, "bunker", 599, "truck", 599, "scout", 599),
                Map.of("htank", 600),
                Map.of("bunker", new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE),
                        "scout", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT)), Map.of());

        assertEquals((int) (UnitDef.UNITS.get("infantry").spd() * 50), state.attackerPositions().get("infantry"));
        assertEquals(599, state.attackerPositions().get("bunker"));
        assertEquals(599, state.attackerPositions().get("truck"));
        assertEquals(Math.max(0, 599 - (int) (UnitDef.UNITS.get("scout").spd() * 50)), state.attackerPositions().get("scout"));
        String bunkerLine = state.log().lines().filter(line -> line.startsWith("我方碉堡")).findFirst().orElseThrow();
        assertTrue(bunkerLine.contains("未能推进"), bunkerLine);
        assertTrue(bunkerLine.contains("距敌1"), bunkerLine);
        assertFalse(bunkerLine.contains("已接触前排"), bunkerLine);
    }

    @Test
    void retreatingRearTargetIsNotMovedBackToContact() {
        BattleRoundState state = round(Map.of("htank", 100_000),
                Map.of("destroyer", 100_000, "htank", 100_000),
                Map.of("htank", 100), Map.of("destroyer", 100, "htank", 101), Map.of(),
                Map.of("htank", new BattleService.UnitOrder(BattleService.CommandAction.RETREAT)));
        assertEquals(101 + (int) (UnitDef.UNITS.get("htank").spd() * 50), state.defenderPositions().get("htank"));
        assertFalse(heavyTankLine(state, "我方").contains("贴脸×"), state.log());
    }

    @Test
    void savedBattleWithEarlierCrossedAirUnitsStillClosesTheTankContactGap() {
        BattleRoundState state = new BattleService(0).resolveWorldRound(Map.of("htank", 100_000, "rocket", 100_000),
                Map.of("destroyer", 100_000, "htank", 100_000, "fighter", 100_000, "bomber", 100_000),
                Map.of("htank", 2_992, "rocket", 2_025),
                Map.of("destroyer", 2_992, "htank", 2_994, "fighter", 2_674, "bomber", 2_982),
                6_950, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, Map.of(), Map.of());
        assertEquals(state.attackerPositions().get("htank"), state.defenderPositions().get("htank"), state.log());
        assertTrue(heavyTankLine(state, "我方").contains("贴脸×1.5"), state.log());
        for (int own : state.attackerPositions().values()) for (int foe : state.defenderPositions().values()) {
            assertTrue(own <= foe, "历史会话的空军也不能留在敌方前排后面：" + state.log());
        }
    }

    @Test
    void crossedHoldingUnitsAreRestoredWithoutMovingValidRearUnits() {
        BattleRoundState state = round(Map.of("htank", 100_000, "rocket", 100_000),
                Map.of("fighter", 100_000, "htank", 100_000),
                Map.of("htank", 600, "rocket", 100), Map.of("fighter", 400, "htank", 900),
                Map.of("htank", HOLD, "rocket", HOLD), Map.of("fighter", HOLD, "htank", HOLD));
        assertEquals(500, state.attackerPositions().get("htank"));
        assertEquals(500, state.defenderPositions().get("fighter"));
        assertEquals(100, state.attackerPositions().get("rocket"));
        assertEquals(900, state.defenderPositions().get("htank"));
    }

    @Test
    void variedFormationsRespectSpeedBoundsOrdersAndBlockingWithTechGearAndSkillCycles() {
        Random random = new Random(20261012);
        List<String> units = new ArrayList<>(UnitDef.UNITS.keySet());
        units.addAll(FortDef.FORTS.keySet());
        Collections.sort(units);
        for (int sample = 0; sample < 160; sample++) {
            Collections.shuffle(units, random);
            Map<String, Integer> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
            Map<String, Integer> ap = new LinkedHashMap<>(), bp = new LinkedHashMap<>();
            Map<String, BattleService.UnitOrder> ao = new LinkedHashMap<>(), bo = new LinkedHashMap<>();
            Map<String, Integer> tech = new LinkedHashMap<>(Map.of("arm_engine", sample % 11,
                    "air_engine", sample % 11, "nav_engine", sample % 11));
            for (int i = 0; i < 6; i++) {
                String own = units.get(i), foe = units.get(i + 6);
                a.put(own, 100_000); b.put(foe, 100_000);
                ap.put(own, random.nextInt(501)); bp.put(foe, 500 + random.nextInt(501));
                for (String id : List.of(own, foe)) tech.put(UnitEquipmentEffects.key(id, "speed"), 15);
                if (sample % 4 != 0) {
                    ao.put(own, new BattleService.UnitOrder(BattleService.CommandAction.values()[random.nextInt(3)]));
                    bo.put(foe, new BattleService.UnitOrder(BattleService.CommandAction.values()[random.nextInt(3)]));
                }
            }
            var ctx = new BattleService.TechCtx(tech, Map.of("blitz", 5), 0, 0,
                    new CommandBonus(null, "", 0, 0, 0, 250));
            BattleService service = new BattleService(0);
            BattleRoundState state = service.resolvePracticeRound(a, b, ap, bp, 1_000, ctx, ctx,
                    sample % 2 == 0 ? 4 : 5, 4, ao, bo);
            for (var event : service.practiceEvents()) {
                if (!"MOVEMENT".equals(event.get("type"))) continue;
                int from = ((Number) event.get("from")).intValue(), to = ((Number) event.get("to")).intValue();
                int budget = (int) (((Number) event.get("speed")).doubleValue() * 50);
                assertTrue(Math.abs(to - from) <= budget, event.toString());
                assertTrue(to >= 0 && to <= 1_000, event.toString());
                boolean mine = "A".equals(event.get("side"));
                String id = (String) event.get("unit");
                var order = (mine ? ao : bo).get(id);
                boolean autoAdvance = UnitDef.UNITS.containsKey(id)
                        ? UnitDef.UNITS.get(id).autoAdvance() == null || UnitDef.UNITS.get(id).autoAdvance()
                        : FortDef.FORTS.get(id).autoAdvance();
                var action = order != null ? order.action() : autoAdvance ? BattleService.CommandAction.ADVANCE : BattleService.CommandAction.HOLD;
                if (action == BattleService.CommandAction.HOLD) assertEquals(from, to, event.toString());
                else if (action == BattleService.CommandAction.ADVANCE) assertTrue(mine ? to >= from : to <= from, event.toString());
                else assertTrue(mine ? to <= from : to >= from, event.toString());
            }
            for (String own : a.keySet()) for (String foe : b.keySet()) {
                assertTrue(state.attackerPositions().get(own) <= state.defenderPositions().get(foe), state.log());
            }
            BattleRoundState reordered = new BattleService(0, false).resolvePracticeRound(
                    reversed(a), reversed(b), ap, bp, 1_000, ctx, ctx, sample % 2 == 0 ? 4 : 5, 4, ao, bo);
            assertEquals(state.attackerPositions(), reordered.attackerPositions());
            assertEquals(state.defenderPositions(), reordered.defenderPositions());
        }
    }

    private Map<String, Integer> reversed(Map<String, Integer> source) {
        List<String> keys = new ArrayList<>(source.keySet());
        Collections.reverse(keys);
        Map<String, Integer> result = new LinkedHashMap<>();
        keys.forEach(id -> result.put(id, source.get(id)));
        return result;
    }

    private BattleRoundState round(Map<String, Integer> a, Map<String, Integer> b,
                                    Map<String, Integer> ap, Map<String, Integer> bp,
                                    Map<String, BattleService.UnitOrder> ao, Map<String, BattleService.UnitOrder> bo) {
        return new BattleService(0).resolveWorldRound(a, b, ap, bp, 1_000,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 1, ao, bo);
    }

    private String heavyTankLine(BattleRoundState state, String prefix) {
        return state.log().lines().filter(line -> line.startsWith(prefix + UnitDef.UNITS.get("htank").name()))
                .findFirst().orElseThrow();
    }
}
