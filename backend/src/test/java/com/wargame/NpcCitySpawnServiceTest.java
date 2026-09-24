package com.wargame;

import com.wargame.service.NpcCitySpawnService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NpcCitySpawnServiceTest extends BaseServiceTest {
    @Autowired NpcCitySpawnService spawns;

    @Test
    void conqueredCityDisappearsAndReplacementHasNewLandCoordinatesAndNoNavy() {
        var world = createTestWorld();
        createTestPlayer("npc-city-neighbor", 30);
        spawns.ensurePopulation(world.getId());
        spawns.ensurePopulation(world.getId());
        var oldCity = npcCityRepository.findByWorldId(world.getId()).get(0);
        int oldX = oldCity.getX();
        int oldY = oldCity.getY();
        Long oldId = oldCity.getId();

        var replacement = spawns.replace(oldCity);
        assertFalse(npcCityRepository.existsById(oldId));
        assertEquals(12, npcCityRepository.findByWorldId(world.getId()).size());
        assertFalse(oldX == replacement.getX() && oldY == replacement.getY());
        assertFalse(WorldTerrainService.sea(worldMapRepository.findById(world.getId()).orElseThrow().getTerrainData(),
                replacement.getX(), replacement.getY()));
        assertFalse(replacement.getX() >= 10 && replacement.getX() <= 11 && replacement.getY() >= 10 && replacement.getY() <= 11);
        for (var city : npcCityRepository.findByWorldId(world.getId())) {
            assertTrue(JsonUtil.parseIntMap(city.getArmy()).keySet().stream()
                    .noneMatch(Set.of("destroyer", "sub", "battleship", "carrier")::contains));
            assertFalse(JsonUtil.parseIntMap(city.getResources()).isEmpty());
        }
    }
}
