package com.wargame;

import com.wargame.service.ImportService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BanditImportTest extends BaseServiceTest {
    @Autowired ImportService imports;

    @Test
    void legacySaveIgnoresCitiesNormalizesLandAndPreservesSeaShips() {
        var world = createTestWorld();
        char[] mask = "0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE).toCharArray();
        mask[20 * WorldTerrainService.SIZE + 20] = '1';
        world.setTerrainData(new String(mask));
        worldMapRepository.saveAndFlush(world);
        var player = createTestPlayer();
        imports.importFromJson(player.getId(), """
                {"world":{"npcCities":[{"id":99,"name":"旧要塞","x":15,"y":15}],
                  "wildTiles":[],"bandits":[
                    {"name":"雇佣兵基地 Lv.7","level":7,"x":18,"y":18,"army":{"infantry":5},"defeated":true},
                    {"name":"日寇海域守军 Lv.9","level":9,"x":20,"y":20,"army":{"destroyer":2,"sub":3},"defeated":false}
                  ]}}
                """);
        assertEquals(0, npcCityRepository.count());
        var bandits = banditRepository.findByWorldId(world.getId());
        assertEquals(2, bandits.size());
        var land = bandits.stream().filter(b -> b.getX() == 18).findFirst().orElseThrow();
        assertEquals("寇 Lv.7", land.getName()); assertEquals(7, land.getLevel());
        assertEquals(Map.of("infantry", 5), JsonUtil.parseIntMap(land.getArmy()));
        assertTrue(land.getDefeated());
        var sea = bandits.stream().filter(b -> b.getX() == 20).findFirst().orElseThrow();
        assertEquals("日寇海域守军 Lv.9", sea.getName()); assertEquals(9, sea.getLevel());
        assertEquals(Map.of("destroyer", 2, "sub", 3), JsonUtil.parseIntMap(sea.getArmy()));
        assertFalse(sea.getDefeated());
    }
}
