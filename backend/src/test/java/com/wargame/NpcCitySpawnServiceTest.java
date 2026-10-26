package com.wargame;

import com.wargame.service.NpcCitySpawnService;
import com.wargame.service.WorldTerrainService;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.NpcCity;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldViewService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NpcCitySpawnServiceTest extends BaseServiceTest {
    @Autowired NpcCitySpawnService spawns;
    @Autowired com.wargame.service.NpcFootprintService footprints;
    @Autowired BattleSessionRepository battles;
    @Autowired WorldMapService map;
    @Autowired WorldViewService view;

    @Test
    void newWorldHasLandBanditsAndSeaGuardsWithoutNpcCities() {
        var world = gameStateService.genWorld(null);
        assertEquals(0, npcCityRepository.count());
        var targets = banditRepository.findByWorldId(world.getId());
        assertFalse(targets.isEmpty());
        assertTrue(targets.stream().anyMatch(b -> WorldTerrainService.sea(world.getTerrainData(), b.getX(), b.getY())));
        assertTrue(targets.stream().filter(b -> !WorldTerrainService.sea(world.getTerrainData(), b.getX(), b.getY()))
                .allMatch(b -> ("寇 Lv." + b.getLevel()).equals(b.getName())));
        assertFalse(wildTileRepository.findByWorldId(world.getId()).isEmpty());
    }

    @Test
    void legacyAdjacentCitiesAndBanditsAreMovedWithoutResettingTheirState() {
        var world = createTestWorld();
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.saveAndFlush(world);
        var city = legacyCity(world.getId(), 2, 96);
        var bandit = new com.wargame.model.entity.Bandit();
        bandit.setWorldId(world.getId()); bandit.setX(3); bandit.setY(96);
        bandit.setLevel(7); bandit.setName("旧据点"); bandit.setArmy("{\"infantry\":5}");
        bandit.setDefeated(true);
        banditRepository.saveAndFlush(bandit);
        Long id = bandit.getId();
        footprints.repair(world.getId());
        assertFalse(city.getX().equals(bandit.getX()) && city.getY().equals(bandit.getY()));
        assertTrue(Math.abs(city.getX() - bandit.getX()) >= 2 || Math.abs(city.getY() - bandit.getY()) >= 2,
                "单格 NPC 之间至少留一格空隙");
        assertEquals(id, bandit.getId());
        assertEquals("{\"infantry\":5}", bandit.getArmy());
        assertTrue(bandit.getDefeated());
        int x = city.getX(), y = city.getY(), bx = bandit.getX(), by = bandit.getY();
        footprints.repair(world.getId());
        assertEquals(x, city.getX()); assertEquals(y, city.getY());
        assertEquals(bx, bandit.getX()); assertEquals(by, bandit.getY());
    }

    @Test
    void inactiveCitiesAreRemovedWhileMarchAndBattleReferencesAreRetained() {
        var world = createTestWorld();
        var player = createTestPlayer();
        var idle = legacyCity(world.getId(), 20, 20);
        var active = legacyCity(world.getId(), 22, 22);
        var march = createMarch(player.getId(), "npc", String.valueOf(active.getId()), active.getName(),
                10, 10, 22, 22, Map.of("infantry", 100), "conquer", 1, 2, false, false);
        march.setBattleMode("manual");
        marchRepository.saveAndFlush(march);
        marchService.processMarches(player.getId(), 2);
        var battle = battles.findByMarchId(march.getId()).orElseThrow();
        spawns.removeInactive(world.getId());
        assertFalse(npcCityRepository.existsById(idle.getId()));
        assertTrue(npcCityRepository.existsById(active.getId()));
        marchRepository.delete(march);
        marchRepository.flush();
        spawns.removeInactive(world.getId());
        assertTrue(npcCityRepository.existsById(active.getId()), "战斗引用也必须保护旧目标");
        battles.delete(battle);
        battles.flush();
        spawns.removeInactive(world.getId());
        assertEquals(0, npcCityRepository.count());
    }

    @Test
    void legacyCitiesAreHiddenAndNewRequestsFailWithoutConsumingTroopsOrResources() {
        var world = createTestWorld();
        var player = createTestPlayer();
        var city = legacyCity(world.getId(), 20, 20);
        createArmyUnit(player.getId(), "infantry", 100);
        long oil = getResources(player.getId()).getOil();
        assertEquals(List.of(), view.getWorld(player.getId(), 20, 20, 5).get("npcCities"));
        assertEquals(List.of(), worldService.getNearbyCities(player.getId()).get("npcCities"));
        assertTrue(worldService.findAtCoordinate(player.getId(), 20, 20).isEmpty());
        assertTrue(((List<?>) map.chunk(player.getId(), 1, 1).get("targets")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> map.target(player.getId(), "npc", city.getId()));
        var request = new DispatchRequest("npc", city.getId(), "conquer", Map.of("infantry", 10), null, null);
        assertEquals("NPC城市/要塞已取消", assertThrows(IllegalArgumentException.class,
                () -> marchService.previewRoute(player.getId(), request)).getMessage());
        assertEquals("NPC城市/要塞已取消", assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(player.getId(), request)).getMessage());
        assertEquals(oil, getResources(player.getId()).getOil());
        assertEquals(100, armyUnitRepository.findByPlayerIdAndType(player.getId(), "infantry").get(0).getCount());
        assertEquals(0, marchRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "manual"})
    void legacyVictoriesSettleWithoutSpawningReplacementCities(String mode) {
        var world = createTestWorld();
        var player = createTestPlayer();
        var city = legacyCity(world.getId(), 20, 20);
        var march = createMarch(player.getId(), "npc", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 100), "conquer", 1, 2, false, false);
        march.setBattleMode(mode);
        marchRepository.saveAndFlush(march);
        marchService.processMarches(player.getId(), 2);
        if ("manual".equals(mode)) {
            Long battleId = battles.findByMarchId(march.getId()).orElseThrow().getId();
            for (int round = 1; round <= 30 && battles.existsById(battleId); round++)
                marchService.processTimedOutTacticalBattle(battleId, 2 + round * 15_000L);
        }
        assertTrue(march.getReturning());
        assertTrue(march.getBattleWon());
        assertTrue(city.getDefeated());
        assertEquals("{}", city.getArmy());
        assertEquals(1, npcCityRepository.count());
        spawns.removeInactive(world.getId());
        assertTrue(npcCityRepository.existsById(city.getId()), "返程行军结束前仍须保留旧目标");
        marchService.processMarches(player.getId(), march.getArriveAt());
        spawns.removeInactive(world.getId());
        assertEquals(0, npcCityRepository.count());
    }

    private NpcCity legacyCity(Long worldId, int x, int y) {
        var city = new NpcCity();
        city.setWorldId(worldId); city.setX(x); city.setY(y); city.setLevel(1);
        city.setName("旧要塞"); city.setArmy("{\"infantry\":1}"); city.setForts("{}");
        city.setResources("{}"); city.setDefeated(false);
        return npcCityRepository.saveAndFlush(city);
    }
}
