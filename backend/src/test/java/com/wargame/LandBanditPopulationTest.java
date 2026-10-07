package com.wargame;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.service.LandBanditPopulationService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LandBanditPopulationTest extends BaseServiceTest {
    @Autowired LandBanditPopulationService population;

    @Test
    void quotasAreExactIdempotentAndPreserveDefeatedAndSeaTargets() {
        var world = createTestWorld();
        world.setTerrainData("0001");
        worldMapRepository.saveAndFlush(world);
        Bandit defeated = bandit(world.getId(), 0, 0, 1, true);
        Bandit sea = bandit(world.getId(), 1, 1, 30, false);
        var wild = createWildTile(world.getId(), "grainfield", 2, 0, 1, Map.of(), 100);
        var player = createTestPlayer("population-city", 30);

        population.ensure(world.getId());
        var all = banditRepository.findByWorldId(world.getId());
        var land = all.stream().filter(b -> !WorldTerrainService.sea(world.getTerrainData(), b.getX(), b.getY())).toList();
        assertEquals(3300, land.size());
        var counts = land.stream().collect(Collectors.groupingBy(Bandit::getLevel, Collectors.counting()));
        for (int level = 1; level <= 30; level++) assertEquals((long) WorldConfig.landBanditQuota(level), counts.get(level));
        assertTrue(defeated.getDefeated());
        assertEquals("{}", defeated.getArmy());
        assertEquals("{\"infantry\":7}", sea.getArmy());
        var coordinates = land.stream().map(b -> b.getX() + "," + b.getY()).collect(Collectors.toSet());
        assertEquals(3300, coordinates.size());
        assertFalse(coordinates.contains(wild.getX() + "," + wild.getY()));
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
            assertFalse(coordinates.contains((player.getCityPosX() + dx) + "," + (player.getCityPosY() + dy)));
        var occupied = new java.util.HashSet<String>();
        for (var npc : land) {
            assertTrue(npc.getX() + 2 <= WorldConfig.SIZE && npc.getY() + 2 <= WorldConfig.SIZE);
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                String cell = (npc.getX() + dx) + "," + (npc.getY() + dy);
                assertTrue(occupied.add(cell), "据点四格不得重叠：" + cell);
                assertFalse(cell.equals(wild.getX() + "," + wild.getY()));
            }
        }
        var high = land.stream().filter(b -> b.getLevel() == 30).findFirst().orElseThrow();
        assertEquals(WorldConfig.BANDIT_LEVELS.get(29).army(), JsonUtil.parseIntMap(high.getArmy()));
        var ids = all.stream().map(Bandit::getId).collect(Collectors.toSet());
        population.ensure(world.getId());
        assertEquals(ids, banditRepository.findByWorldId(world.getId()).stream().map(Bandit::getId).collect(Collectors.toSet()));

        banditRepository.delete(high);
        banditRepository.flush();
        population.ensure(world.getId());
        assertEquals(3301, banditRepository.findByWorldId(world.getId()).size());
        assertFalse(banditRepository.existsById(high.getId()));
        assertTrue(defeated.getDefeated());
    }

    @Test
    void surplusReferencedByMarchIsKeptUntilMarchEnds() {
        var world = createTestWorld();
        world.setSize(1);
        world.setTerrainData("0");
        worldMapRepository.saveAndFlush(world);
        for (int i = 0; i < 81; i++) bandit(world.getId(), 0, 0, 30, true);
        var excess = banditRepository.findByWorldId(world.getId()).stream().max(java.util.Comparator.comparing(Bandit::getId)).orElseThrow();
        var player = createTestPlayer("population-march", 30);
        var march = createMarch(player.getId(), "bandit", String.valueOf(excess.getId()), excess.getName(),
                10, 10, 0, 0, Map.of("infantry", 1), "conquer", 1, 2, false, false);
        population.ensure(world.getId());
        assertEquals(81, banditRepository.findByWorldId(world.getId()).size());
        marchRepository.delete(march);
        marchRepository.flush();
        population.ensure(world.getId());
        assertEquals(80, banditRepository.findByWorldId(world.getId()).size());
        assertFalse(banditRepository.existsById(excess.getId()));
    }

    @Test
    void smallWorldWithoutSpaceDefersMissingQuotas() {
        var world = createTestWorld();
        world.setSize(2);
        world.setTerrainData("0000");
        worldMapRepository.saveAndFlush(world);
        population.ensure(world.getId());
        assertEquals(1, banditRepository.findByWorldId(world.getId()).size());
        population.ensure(world.getId());
        assertEquals(1, banditRepository.findByWorldId(world.getId()).size());
        assertThrows(IllegalArgumentException.class, () -> WorldConfig.landBanditQuota(0));
        assertThrows(IllegalArgumentException.class, () -> WorldConfig.landBanditQuota(31));
    }

    @Test
    void fourCellPlacementRejectsCoastEdgesAndAnyOccupiedCell() {
        var used = new java.util.HashSet<String>();
        assertTrue(WorldTerrainService.vacantFootprint("0000", 2, false, used, 0, 0, 2));
        assertFalse(WorldTerrainService.vacantFootprint("0001", 2, false, used, 0, 0, 2));
        assertFalse(WorldTerrainService.vacantFootprint("0000", 2, false, used, 1, 0, 2));
        used.add("1,1");
        assertFalse(WorldTerrainService.vacantFootprint("0000", 2, false, used, 0, 0, 2));
        used.clear();
        assertTrue(WorldTerrainService.vacantFootprint("1111", 2, true, used, 0, 0, 2));
        WorldTerrainService.reserveFootprint(used, 0, 0, 2);
        assertEquals(4, used.size());
    }

    private Bandit bandit(Long worldId, int x, int y, int level, boolean defeated) {
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName("测试据点"); npc.setArmy(defeated ? "{}" : "{\"infantry\":7}"); npc.setDefeated(defeated);
        return banditRepository.saveAndFlush(npc);
    }
}
