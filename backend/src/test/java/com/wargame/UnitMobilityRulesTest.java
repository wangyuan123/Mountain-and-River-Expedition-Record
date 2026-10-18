package com.wargame;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.FortDef;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证全部兵种新基础档位、科技覆盖和技能窗口中的真实移动。 */
class UnitMobilityRulesTest {
    @ParameterizedTest
    @CsvSource({"infantry,2", "truck,4", "armored,5", "recon_car,5", "ltank,4", "htank,4",
            "assault,2", "rocket,3", "special,6", "scout,9", "fighter,8", "bomber,6",
            "transport,6", "destroyer,5", "sub,3", "battleship,4", "carrier,4"})
    void fullTechnologyAndBlitzUseReducedBaseSpeed(String id, int base) {
        var unit = UnitDef.UNITS.get(id);
        assertEquals(base, unit.spd());
        var tech = Map.of("arm_engine", 10, "air_engine", 10, "nav_engine", 10);
        var ctx = new BattleService.TechCtx(tech, Map.of("blitz", 5), 0);
        var engine = new BattleService(0);
        assertEquals(base * 1.5, engine.practiceStats(id, ctx).get("speed"), 1e-9);
        var order = Map.of(id, new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE));
        for (int round : new int[]{1, 2, 4}) {
            engine.resolvePracticeRound(Map.of(id, 1), Map.of("infantry", 1), Map.of(id, 0),
                    Map.of("infantry", 100000), 100000, ctx, new BattleService.TechCtx(Map.of(), Map.of(), 0),
                    round, round == 1 ? 0 : 1, order, Map.of());
            var movement = engine.practiceEvents().stream().filter(e -> "MOVEMENT".equals(e.get("type")) && "A".equals(e.get("side"))).findFirst().orElseThrow();
            double speed = base * 1.5 * (round == 4 ? 1.3 : 1);
            assertEquals(speed, ((Number) movement.get("speed")).doubleValue(), 1e-9);
            assertEquals((int) (speed * 50), movement.get("to"));
        }
    }

    @Test
    void infantrySharesGroundTechnologyWithoutChangingCombatCategoryAndFortsStayStill() {
        for (String id : new String[]{"infantry", "special", "truck"}) {
            assertEquals("inf", UnitDef.UNITS.get(id).cat());
            assertEquals("arm", UnitDef.UNITS.get(id).speedCategory());
        }
        var ctx = new BattleService.TechCtx(Map.of("arm_engine", 10), Map.of("blitz", 5), 0, 0,
                new CommandBonus(1L, "司令", 0, 0, 0, 200));
        var engine = new BattleService(0);
        FortDef.FORTS.forEach((id, fort) -> assertEquals(0, engine.practiceStats(id, ctx).get("speed")));
    }
}
