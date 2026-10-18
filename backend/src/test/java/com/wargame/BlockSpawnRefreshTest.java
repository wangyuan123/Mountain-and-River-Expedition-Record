package com.wargame;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.service.WildTileRefreshScheduler;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockSpawnRefreshTest extends BaseServiceTest {
    @Autowired WorldTerrainService terrain;
    @Autowired com.wargame.service.LandBanditPopulationService landBandits;

    @Test
    void refreshBalancesResourcesAndNpcsSeparatelyUsingVisibleTargets() {
        var world = createTestWorld();
        world.setSize(32);
        world.setTerrainData("0".repeat(WorldConfig.SIZE * WorldConfig.SIZE));
        worldMapRepository.saveAndFlush(world);
        for (int n = 0; n < 6; n++) {
            createWildTile(world.getId(), "oil", 2 + n, 2, 2, Map.of(), 320_000);
            var hidden = createWildTile(world.getId(), "grainfield", 2 + n, 3, 3, Map.of(), 480_000);
            hidden.setMined(hidden.getTotalRes());
            hidden.setDepletedAt(1L);
            wildTileRepository.save(hidden);
            bandit(world.getId(), 20 + n, 20, false);
            bandit(world.getId(), 20 + n, 21, true);
        }
        new WildTileRefreshScheduler(worldMapRepository, wildTileRepository, playerCityRepository,
                npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits)
                .refreshDepletedWilds();
        int[] resources = new int[4], npcs = new int[4];
        for (var tile : wildTileRepository.findByWorldId(world.getId())) {
            assertFalse(tile.isDormant());
            resources[tile.getY() / 16 * 2 + tile.getX() / 16]++;
            if ("grainfield".equals(tile.getType())) {
                assertFalse(tile.getY() == 3 && tile.getX() >= 2 && tile.getX() <= 7);
                assertEquals(0, tile.getMined());
                assertEquals(480_000, tile.getTotalRes());
            }
        }
        for (var npc : banditRepository.findByWorldId(world.getId())) {
            assertFalse(npc.getDefeated());
            npcs[npc.getY() / 16 * 2 + npc.getX() / 16]++;
            assertEquals(2, npc.getLevel());
            assertEquals("测试军官", npc.getCommanderName());
        }
        assertArrayEquals(new int[]{6, 2, 2, 2}, resources);
        assertArrayEquals(new int[]{2, 2, 2, 6}, npcs);
    }

    @Test
    void defeatedBanditRespawnsInTheSparseBlockWithoutChangingIdentityOrLevel() {
        var world = createTestWorld();
        world.setSize(WorldConfig.WORLD_SIZE);
        world.setTerrainVersion(WorldTerrainService.TERRAIN_VERSION);
        world.setTerrainData("0".repeat(WorldConfig.SIZE * WorldConfig.SIZE));
        worldMapRepository.saveAndFlush(world);
        createTestPlayer("block-spawn-player", 30);
        for (int y = 0; y < WorldConfig.WORLD_SIZE; y += 16) for (int x = 0; x < WorldConfig.WORLD_SIZE; x += 16) {
            if (x == 0 && y == 0) continue;
            bandit(world.getId(), x + 8, y + 8, false);
        }
        bandit(world.getId(), 18, 18, true);
        var old = banditRepository.findByWorldId(world.getId()).stream()
                .filter(b -> Boolean.TRUE.equals(b.getDefeated())).findFirst().orElseThrow();
        Long oldId = old.getId();
        new WildTileRefreshScheduler(worldMapRepository, wildTileRepository, playerCityRepository,
                npcCityRepository, banditRepository, playerRepository, marchRepository, terrain, landBandits)
                .refreshDepletedWilds();
        var replacement = banditRepository.findById(oldId).orElseThrow();
        assertTrue(replacement.getX() < 16 && replacement.getY() < 16);
        assertFalse(replacement.getX() >= 10 && replacement.getX() <= 11
                && replacement.getY() >= 10 && replacement.getY() <= 11);
        assertEquals(2, replacement.getLevel());
        assertEquals("寇 Lv.2", replacement.getName());
        assertEquals("测试军官", replacement.getCommanderName());
        assertFalse(replacement.getDefeated());
        assertEquals(WorldConfig.landNpcGarrison(2, replacement.getX(), replacement.getY()),
                JsonUtil.parseIntMap(replacement.getArmy()));
        assertEquals(0, npcCityRepository.findByWorldId(world.getId()).size());
    }

    private void bandit(Long worldId, int x, int y, boolean defeated) {
        Bandit npc = new Bandit();
        npc.setWorldId(worldId); npc.setX(x); npc.setY(y); npc.setLevel(2);
        npc.setName("测试据点"); npc.setCommanderName("测试军官"); npc.setDefeated(defeated);
        npc.setArmy(defeated ? "{}" : "{\"infantry\":7}");
        banditRepository.save(npc);
    }
}
