package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyReworkRulesTest {
    @Test void reinforcementClampsAndOnlyMultipliesExistingWall() {
        var wall = ConquestWallState.create(10, 10, false, BattleRules.VERSION);
        assertEquals(new BigDecimal("3750000.000000"), wall.maxHp());
        assertEquals(300, wall.defense());
        assertEquals(wall, ConquestWallState.create(10, 50, false, BattleRules.VERSION));
        assertEquals(0, ConquestWallState.create(0, 10, false, BattleRules.VERSION).maxHp().signum());
        assertEquals(21, ConquestWallState.create(1, 1, false, BattleRules.VERSION).defense());
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9,10})
    void radarWhitelistSeparatesTypesTotalsAndExactCounts(int level) {
        Map<String, Object> raw = new LinkedHashMap<>(Map.of("id", "march-1", "marchId", 1L, "fromName", "秘密玩家", "fromX", 99,
                "army", Map.of("infantry", 111, "fighter", 15), "combatTech", Map.of("attack_tech", 10), "commander", "秘密将领"));
        raw.put("privateAccountResources", 99999);
        raw.put("skills", Map.of("blitz", 5));
        raw.put("commanderAttributes", Map.of("military", 200));
        raw.put("commandBonus", Map.of("name", "司令甲", "military", 100));
        var view = IncomingIntelligenceService.project(raw, level);
        assertEquals(level >= 1, view.containsKey("fromName"));
        assertEquals(level >= 3, view.containsKey("branches"));
        assertEquals(level >= 6, view.containsKey("totalTroops"));
        assertEquals(level >= 7, view.containsKey("army"));
        assertEquals(level >= 8, view.containsKey("commander"));
        assertEquals(level >= 9, view.containsKey("combatTech"));
        assertFalse(view.containsKey("privateAccountResources"));
        assertEquals(level >= 2, view.containsKey("fromX"));
        assertEquals(level >= 4, view.containsKey("strengthBand"));
        assertEquals(level >= 5, view.containsKey("unitTypes"));
        assertFalse(view.containsKey("skills"), "来袭情报不公开带兵将领技能");
        assertFalse(view.containsKey("commanderAttributes"), "来袭情报不公开带兵将领属性");
        assertEquals(level >= 10, view.containsKey("commandBonus"));
        if (level >= 10) assertEquals(raw.get("commandBonus"), view.get("commandBonus"));
        if (level >= 4) assertEquals("101—500", view.get("strengthBand"));
        if (level >= 5) assertEquals(List.of("fighter", "infantry"), view.get("unitTypes"));
    }

    @Test void actualPlunderUsesWarehouseTechnologyAndDoesNotAffectGoldRule() {
        var engine = new BattleService(1);
        var result = engine.finishWorldBattle(true, Map.of("truck", 10), Map.of(), Map.of("truck", 10), Map.of(),
                Map.of(), Map.of("log_warehouse", 10), "plunder", Map.of("food", 20000, "steel", 14000, "gold", 5000), 10, "");
        assertEquals(5000, result.getPlunderedResources().get("food"));
        assertEquals(0, result.getPlunderedResources().get("steel"));
        assertEquals(0, result.getPlunderedResources().get("gold"));
        assertEquals(0, engine.calcProtectCap(0, Map.of("log_warehouse", 10)));
    }
}
