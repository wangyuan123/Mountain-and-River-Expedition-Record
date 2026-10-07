package com.wargame;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.WorldMap;
import com.wargame.service.WildTileRefreshScheduler;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldTerrainService;
import com.wargame.service.WorldViewService;
import com.wargame.service.WorldService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BanditRefreshTest extends BaseServiceTest {
    @Autowired WorldMapService maps;
    @Autowired WorldViewService views;
    @Autowired WorldService worldService;
    @Autowired WorldTerrainService terrain;
    @Autowired com.wargame.service.LandBanditPopulationService landBandits;

    @Test
    void defeatedLandAndSeaTargetsDisappearAndRestoreTheirOwnGarrisons() {
        var player = createTestPlayer("bandit-refresh", 30);
        WorldMap world = createTestWorld();
        world.setSize(WorldConfig.SIZE);
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.saveAndFlush(world);
        int[] land = cell(world, false);
        int[] sea = cell(world, true);
        Bandit landNpc = bandit(world, land[0], land[1], 3, true);
        Bandit seaNpc = bandit(world, sea[0], sea[1], 30, true);
        Bandit active = bandit(world, land[0] + 1, land[1], 1, false);
        String activeArmy = active.getArmy();

        assertHidden(player.getId(), landNpc);
        assertHidden(player.getId(), seaNpc);
        assertEquals(1, ((List<?>) views.getWorld(player.getId(), 0, 0, 0).get("bandits")).size());
        assertTrue(((List<?>) worldService.getNearbyCities(player.getId()).get("bandits")).isEmpty());

        refresh();

        assertRestored(world, landNpc, false, WorldConfig.BANDIT_LEVELS.get(2).army());
        assertRestored(world, seaNpc, true, WorldConfig.seaNpcGarrison(30));
        assertEquals(false, maps.target(player.getId(), "bandit", seaNpc.getId()).get("defeated"));
        assertEquals(activeArmy, banditRepository.findById(active.getId()).orElseThrow().getArmy());
        assertEquals(land[0] + 1, active.getX());
        assertNotEquals(landNpc.getX() + "," + landNpc.getY(), seaNpc.getX() + "," + seaNpc.getY());
    }

    @Test
    void migrationDefersWhenNoCompleteFourCellFootprintIsAvailable() {
        WorldMap world = createTestWorld();
        world.setSize(2);
        world.setTerrainData("0000");
        worldMapRepository.saveAndFlush(world);
        Bandit npc = bandit(world, 0, 0, 2, true);
        Bandit active = bandit(world, 1, 0, 1, false);
        var wild = createWildTile(world.getId(), "grainfield", 0, 1, 1, Map.of(), 160_000);
        wild.setOccupied(true);
        wildTileRepository.saveAndFlush(wild);
        refresh();
        assertTrue(npc.getDefeated());
        assertEquals(0, npc.getX());
        assertEquals(0, npc.getY());
        assertEquals(1, active.getX());
        assertEquals(0, active.getY());
        assertEquals(2, banditRepository.findByWorldId(world.getId()).size());
    }

    @Test
    void pendingMarchDefersMigrationUntilItIsRemoved() {
        var player = createTestPlayer("bandit-march", 30);
        WorldMap world = createTestWorld();
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.saveAndFlush(world);
        Bandit npc = bandit(world, 20, 20, 2, true);
        var march = createMarch(player.getId(), "bandit", String.valueOf(npc.getId()), npc.getName(),
                0, 0, 20, 20, Map.of("infantry", 1), "attack", 1, 2, true, false);
        refresh();
        assertTrue(npc.getDefeated());
        assertEquals(20, npc.getX());
        marchRepository.delete(march);
        marchRepository.flush();
        refresh();
        assertFalse(npc.getDefeated());
    }

    @Test
    void noVacantCellLeavesDefeatedTargetHidden() {
        var player = createTestPlayer("bandit-no-space", 30);
        WorldMap world = createTestWorld();
        world.setSize(1);
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.saveAndFlush(world);
        Bandit npc = bandit(world, 0, 0, 1, true);
        refresh();
        assertTrue(npc.getDefeated());
        assertEquals("{}", npc.getArmy());
        assertHidden(player.getId(), npc);
    }

    private Bandit bandit(WorldMap world, int x, int y, int level, boolean defeated) {
        Bandit npc = new Bandit();
        npc.setWorldId(world.getId()); npc.setX(x); npc.setY(y); npc.setLevel(level);
        npc.setName("测试据点"); npc.setCommanderName("测试军官"); npc.setDefeated(defeated);
        npc.setArmy(defeated ? "{}" : "{\"infantry\":7}");
        return banditRepository.saveAndFlush(npc);
    }

    private int[] cell(WorldMap world, boolean sea) {
        for (int y = 0; y < WorldConfig.SIZE; y++) for (int x = 0; x < WorldConfig.SIZE; x++)
            if (WorldTerrainService.sea(world.getTerrainData(), x, y) == sea) return new int[]{x, y};
        throw new AssertionError("Missing terrain");
    }

    @SuppressWarnings("unchecked")
    private void assertHidden(Long viewer, Bandit npc) {
        var targets = (List<Map<String, Object>>) maps.chunk(viewer, npc.getX() / 16, npc.getY() / 16).get("targets");
        assertFalse(targets.stream().anyMatch(t -> "bandit".equals(t.get("kind")) && npc.getId().equals(t.get("id"))));
        assertThrows(IllegalArgumentException.class, () -> maps.target(viewer, "bandit", npc.getId()));
    }

    private void assertRestored(WorldMap world, Bandit npc, boolean sea, Map<String, Integer> army) {
        // 记录迁移前坐标由首次选择的地形格确定，刷新必须保持等级、军官及地形。
        int[] original = cell(world, sea);
        Bandit refreshed = banditRepository.findById(npc.getId()).orElseThrow();
        assertFalse(refreshed.getDefeated());
        assertNotEquals(original[0] + "," + original[1], refreshed.getX() + "," + refreshed.getY());
        assertEquals(sea, WorldTerrainService.sea(world.getTerrainData(), refreshed.getX(), refreshed.getY()));
        assertEquals(army, JsonUtil.parseIntMap(refreshed.getArmy()));
        assertEquals("测试军官", refreshed.getCommanderName());
    }

    private void refresh() {
        new WildTileRefreshScheduler(worldMapRepository, wildTileRepository, playerCityRepository,
                npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits).refreshDepletedWilds();
    }
}
