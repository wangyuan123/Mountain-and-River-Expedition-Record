package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.TechnologyGoldCosts;
import com.wargame.service.TechService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TechnologyGoldCostsTest {
    @Test
    void all240PricesMatchApprovedDesignWithoutChangingOtherResources() throws Exception {
        var design = new ObjectMapper().readTree(Path.of("../docs/balance/tech-research-gold-design-20261028.json").toFile());
        var service = new TechService(null, null, null, null);
        var covered = new HashSet<String>();
        long total = 0;
        for (var group : design.path("groups")) {
            for (var tech : group.path("technologies")) {
                String key = tech.path("key").asText();
                assertTrue(covered.add(key), key);
                var definition = GameData.TECHS.get(key);
                assertNotNull(definition, key);
                assertFalse(definition.cost().containsKey("gold"), "旧指数黄金配置应移除: " + key);
                int previous = 0;
                long cumulative = 0;
                for (var level : group.path("levels")) {
                    int target = level.path("targetLevel").asInt();
                    int gold = level.path("gold").asInt();
                    var cost = service.calcTechCost(key, target - 1);
                    assertEquals(gold, cost.get("gold"), key + " Lv." + target);
                    assertEquals(gold, TechnologyGoldCosts.forTarget(key, target));
                    assertTrue(gold > previous, key);
                    for (var resource : definition.cost().entrySet()) {
                        assertEquals((int) Math.floor(resource.getValue() * Math.pow(definition.costGrowth(), target - 1)),
                                cost.get(resource.getKey()), key + " " + resource.getKey());
                    }
                    assertEquals(definition.cost().size() + 1, cost.size());
                    cumulative += gold;
                    assertEquals(level.path("cumulativeGold").asLong(), cumulative);
                    total += gold;
                    previous = gold;
                }
            }
        }
        assertEquals(GameData.TECHS.keySet(), covered);
        assertEquals(24, covered.size());
        assertEquals(2_019_962_000L, total);
        assertEquals(Map.of("steel", 240, "food", 120, "gold", 6000), service.calcTechCost("attack_tech", 0));
        assertEquals(20_000_000, service.calcTechCost("prod_food", 9).get("gold"));
        assertEquals(30_000_000, service.calcTechCost("attack_tech", 9).get("gold"));
        assertEquals(50_000_000, service.calcTechCost("arm_engine", 9).get("gold"));
        assertEquals(65_000_000, service.calcTechCost("air_engine", 9).get("gold"));
        assertEquals(80_000_000, service.calcTechCost("nav_engine", 9).get("gold"));
        assertTrue(service.calcTechCost("unknown", 0).isEmpty());
    }
}
