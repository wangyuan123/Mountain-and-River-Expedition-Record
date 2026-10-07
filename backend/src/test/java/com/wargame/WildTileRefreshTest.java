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

import static org.junit.jupiter.api.Assertions.*;

class WildTileRefreshTest extends BaseServiceTest {
    @Autowired WorldMapService maps;
    @Autowired WorldTerrainService terrain;
    @Autowired com.wargame.service.LandBanditPopulationService landBandits;

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
    void refreshRunsAtEightFixedBeijingTimes() throws NoSuchMethodException {
        Scheduled schedule = WildTileRefreshScheduler.class.getMethod("refreshWorldTargets")
                .getAnnotation(Scheduled.class);
        assertEquals("0 0 0,3,6,9,12,15,18,21 * * *", schedule.cron());
        assertEquals("Asia/Shanghai", schedule.zone());
    }
}
