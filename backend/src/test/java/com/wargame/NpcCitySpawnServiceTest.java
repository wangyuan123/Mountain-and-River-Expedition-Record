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
    @Autowired com.wargame.service.NpcFootprintService footprints;

    @Test
    void legacyAdjacentCitiesAndBanditsAreMovedWithoutResettingTheirState() {
        var world = createTestWorld();
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.saveAndFlush(world);
        var city = spawns.spawnAt(world.getId(), 2, 96);
        var bandit = new com.wargame.model.entity.Bandit();
        bandit.setWorldId(world.getId()); bandit.setX(4); bandit.setY(96);
        bandit.setLevel(7); bandit.setName("旧据点"); bandit.setArmy("{\"infantry\":5}");
        bandit.setDefeated(true);
        banditRepository.saveAndFlush(bandit);
        Long id = bandit.getId();
        footprints.repair(world.getId());
        assertFalse(city.getX() < bandit.getX() + 2 && city.getX() + 2 > bandit.getX()
                && city.getY() < bandit.getY() + 2 && city.getY() + 2 > bandit.getY());
        assertTrue(Math.abs(city.getX() - bandit.getX()) >= 3 || Math.abs(city.getY() - bandit.getY()) >= 3,
                "四格 NPC 之间至少留一格空隙");
        assertEquals(id, bandit.getId());
        assertEquals("{\"infantry\":5}", bandit.getArmy());
        assertTrue(bandit.getDefeated());
        int x = city.getX(), y = city.getY(), bx = bandit.getX(), by = bandit.getY();
        footprints.repair(world.getId());
        assertEquals(x, city.getX()); assertEquals(y, city.getY());
        assertEquals(bx, bandit.getX()); assertEquals(by, bandit.getY());
    }

    @Test
    void conqueredCityDisappearsAndReplacementHasNewLandCoordinatesAndNoNavy() {
        var world = createTestWorld();
        createTestPlayer("npc-city-neighbor", 30);
        spawns.ensurePopulation(world.getId());
        spawns.ensurePopulation(world.getId());
        var existingCity = npcCityRepository.findByWorldId(world.getId()).get(0);
        existingCity.setResources("{\"food\":1,\"diamond\":20}");
        npcCityRepository.save(existingCity);
        spawns.ensurePopulation(world.getId());
        assertEquals(com.wargame.model.constants.WorldConfig.BANDIT_LEVELS.get(existingCity.getLevel() - 1).reward(),
                JsonUtil.parseIntMap(npcCityRepository.findById(existingCity.getId()).orElseThrow().getResources()));
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
