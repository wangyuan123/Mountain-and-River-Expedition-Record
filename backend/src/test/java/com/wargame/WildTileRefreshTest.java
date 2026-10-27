package com.wargame;

import com.wargame.model.entity.WildTile;
import com.wargame.service.WildTileRefreshScheduler;
import com.wargame.service.WorldMapService;
import com.wargame.service.WorldTerrainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WildTileRefreshTest extends BaseServiceTest {
    @Autowired WorldMapService maps;
    @Autowired WorldTerrainService terrain;
    @Autowired com.wargame.service.LandBanditPopulationService landBandits;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test @SuppressWarnings("unchecked")
    void depletedWildDisappearsAndRespawnsAwayFromOriginalPosition() {
        var player = createTestPlayer("wild-refresh", 30);
        var world = createTestWorld();
        world.setTerrainData(WorldTerrainService.generate());
        worldMapRepository.save(world);
        WildTile tile = createWildTile(world.getId(), "grainfield", 20, 20, 2,
                Map.of("infantry", 20), 320_000);
        tile.setMined(tile.getTotalRes());
        tile.setDepletedAt(System.currentTimeMillis());
        wildTileRepository.saveAndFlush(tile);

        assertTrue(((List<?>) maps.chunk(player.getId(), 1, 1).get("targets")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maps.target(player.getId(), "wild", tile.getId()));

        new WildTileRefreshScheduler(worldMapRepository, wildTileRepository, playerCityRepository,
                npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits)
                .refreshDepletedWilds();

        WildTile refreshed = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertFalse(refreshed.isDormant());
        assertNotEquals("20,20", refreshed.getX() + "," + refreshed.getY());
        assertFalse(WorldTerrainService.sea(world.getTerrainData(), refreshed.getX(), refreshed.getY()));
        assertEquals(0, refreshed.getMined());
        assertEquals(320_000, refreshed.getTotalRes());
        assertNotEquals("{}", refreshed.getGarrison());
    }

    @Test
    void islandWildRefreshAdaptsToResourcesAndDeepForest() {
        var world = createTestWorld();
        String mask = terrain.ensure();
        world.setTerrainData(mask);
        worldMapRepository.save(world);

        // 获取海岛上的坐标
        var islandGroups = WorldTerrainService.islandCells(mask);
        int cell = islandGroups.get(0).get(0);
        int islandX = cell % WorldTerrainService.SIZE, islandY = cell / WorldTerrainService.SIZE;
        assertTrue(WorldTerrainService.isIsland(mask, islandX, islandY));

        WildTile islandTile = createWildTile(world.getId(), "oil", islandX, islandY, 3,
                Map.of("infantry", 30), 480_000);
        islandTile.setMined(islandTile.getTotalRes());
        islandTile.setDepletedAt(System.currentTimeMillis());
        wildTileRepository.saveAndFlush(islandTile);

        new WildTileRefreshScheduler(worldMapRepository, wildTileRepository, playerCityRepository,
                npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits)
                .refreshDepletedWilds();

        WildTile refreshed = wildTileRepository.findById(islandTile.getId()).orElseThrow();
        assertFalse(refreshed.isDormant());
        // 验证野地刷新在海岛陆地中，未坠海也未丢失海岛特性
        assertTrue(WorldTerrainService.isIsland(mask, refreshed.getX(), refreshed.getY()));
        assertFalse(WorldTerrainService.sea(mask, refreshed.getX(), refreshed.getY()));
        assertEquals(0, refreshed.getMined());
        assertEquals(480_000, refreshed.getTotalRes());
        // 验证野地类型适配为资源野地或深林野地
        assertTrue(Set.of("forest", "oil", "ironworks", "grainfield", "rarefactory").contains(refreshed.getType()));
        assertNotEquals("{}", refreshed.getGarrison());
    }

    @Test
    void refreshChecksPersistedGamePeriodsInsteadOfRealWorldCron() throws NoSuchMethodException {
        Scheduled schedule = WildTileRefreshScheduler.class.getMethod("refreshWorldTargets")
                .getAnnotation(Scheduled.class);
        assertEquals(1_000L, schedule.fixedDelay());
        assertEquals("", schedule.cron());
    }

    @Test
    void refreshBoundaryStaysAtBeijingThreeOClockAndDoesNotRepeatWhileClockIsFrozen() throws Exception {
        var scheduler = org.mockito.Mockito.spy(new WildTileRefreshScheduler(worldMapRepository, wildTileRepository,
                playerCityRepository, npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits));
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "jdbc", jdbc);
        org.mockito.Mockito.doNothing().when(scheduler).refreshDepletedWilds();
        jdbc.update("DELETE FROM game_schedule WHERE task_key='world-refresh'");
        long boundary = java.time.Instant.parse("2026-10-20T19:00:00Z").toEpochMilli();
        var time = new java.util.concurrent.atomic.AtomicLong(boundary - 1);
        try (var ignored = com.wargame.util.GameClock.use(time::get)) {
            scheduler.refreshWorldTargets();
            org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never()).refreshDepletedWilds();
            time.set(boundary);
            scheduler.refreshWorldTargets();
            scheduler.refreshWorldTargets();
            org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.times(1)).refreshDepletedWilds();
        }
    }
}
