package com.wargame;

import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.constants.GameData;
import com.wargame.service.BattleService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 验证司令官常驻四属性与现有带兵将领周期的实际伤害、机动和兼容边界。 */
class CommandBonusBattleTest {
    private final Map<String, BattleService.UnitOrder> hold = Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));

    private BattleRoundState round(CommandBonus attacker, CommandBonus defender, int number, int military, Map<String, Integer> skills) {
        return new BattleService(0).resolveWorldRound(Map.of("infantry", 100), Map.of("infantry", 100),
                Map.of("infantry", 0), Map.of("infantry", 100), 1000,
                Map.of(), Map.of(), skills, Map.of(), military, 0, 0, 0, 0, 0, number, 1,
                hold, hold, attacker, defender);
    }

    private double rawFire(BattleRoundState state) {
        var matcher = Pattern.compile("我方步兵.*?本次原始火力([\\d.]+)").matcher(state.log());
        assertTrue(matcher.find(), state.log());
        return Double.parseDouble(matcher.group(1));
    }

    /**
     * 使用无有效射程的卡车作为防守方，避免同类步兵互相攻击改变我方开火前的存活数量。
     */
    private BattleRoundState attackRound(CommandBonus attacker, int number, Map<String, Integer> skills) {
        return new BattleService(0).resolveWorldRound(Map.of("infantry", 100), Map.of("truck", 100),
                Map.of("infantry", 0), Map.of("truck", 100), 1000,
                Map.of(), Map.of(), skills, Map.of(), 100, 0, 0, 0, 0, 0, number, 1,
                hold, Map.of("truck", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)),
                attacker, CommandBonus.NONE);
    }

    @Test void attackBonusIsPermanentAndMultipliesLeaderAttributesAndSkillsOnlyOnTheirOwnRounds() {
        CommandBonus chief = new CommandBonus(1L, "司令", 150, 0, 0, 0);
        double baseAttack = GameData.UNITS.get("infantry").atkGround() * 100;
        double plain = rawFire(attackRound(CommandBonus.NONE, 1, Map.of()));
        assertEquals(baseAttack, plain, 0.01);
        assertEquals(1.15, rawFire(attackRound(chief, 1, Map.of())) / plain, 0.01);
        assertEquals(2.30, rawFire(attackRound(chief, 3, Map.of())) / plain, 0.02);
        assertEquals(1.15 * 1.20, rawFire(attackRound(chief, 1, Map.of("frenzy", 2))) / plain, 0.02);
        assertEquals(1.15, rawFire(attackRound(chief, 2, Map.of("frenzy", 2))) / plain, 0.02);
    }

    @Test void defensiveAttributesAndLogisticsReduceActualLossesOutsideLeaderAttributeRounds() {
        BattleRoundState plain = round(CommandBonus.NONE, CommandBonus.NONE, 1, 0, Map.of());
        BattleRoundState defense = round(CommandBonus.NONE, new CommandBonus(2L, "防御", 0, 219, 0, 0), 1, 0, Map.of());
        BattleRoundState logistics = round(CommandBonus.NONE, new CommandBonus(2L, "后勤", 0, 0, 219, 0), 1, 0, Map.of());
        assertTrue(defense.defenderArmy().get("infantry") > plain.defenderArmy().get("infantry"));
        assertTrue(logistics.defenderArmy().get("infantry") > plain.defenderArmy().get("infantry"));
    }

    @Test void knowledgeChangesMovementAndInitialDistanceButNeverMovesForts() {
        CommandBonus chief = new CommandBonus(1L, "机动", 0, 0, 0, 200);
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(Map.of("infantry", 1), Map.of("bunker", 1),
                Map.of("infantry", 0), Map.of("bunker", 2000), 2000,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 1, 0,
                Map.of(), Map.of(), chief, chief);
        assertEquals(120, state.attackerPositions().get("infantry"));
        assertEquals(2000, state.defenderPositions().get("bunker"));
        var plain = new BattleService.TechCtx(Map.of(), Map.of(), 0, 0);
        var boosted = new BattleService.TechCtx(Map.of(), Map.of(), 0, 0, chief);
        assertEquals(60, service.calcInitialDistance(Map.of("infantry", 1), Map.of("bunker", 1), boosted, plain)
                - service.calcInitialDistance(Map.of("infantry", 1), Map.of("bunker", 1), plain, plain));
    }

    @Test void snapshotsExcludeSkillsAndOldSessionsHaveNoRetroactiveBonus() {
        CommandBonus original = new CommandBonus(7L, "开战司令", 240, 140, 100, 80);
        assertEquals(original, CommandBonus.fromJson(JsonUtil.toJson(original.toMap())));
        assertFalse(original.toMap().containsKey("skills"));
        assertEquals(CommandBonus.NONE, CommandBonus.fromJson(null));
        assertEquals(CommandBonus.NONE, CommandBonus.fromJson("{}"));
        assertEquals(1.24, original.attackMul(), 1e-9);
    }

    @Test void zeroBonusPreservesOldEngineResultsAndPlayerOrders() {
        BattleService service = new BattleService(0);
        BattleRoundState old = service.resolveWorldRound(Map.of("infantry", 100), Map.of("infantry", 100),
                Map.of("infantry", 0), Map.of("infantry", 100), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 1, 1, hold, hold);
        assertEquals(old, round(CommandBonus.NONE, CommandBonus.NONE, 1, 0, Map.of()));
        assertEquals(0, old.attackerPositions().get("infantry"));
    }
}
