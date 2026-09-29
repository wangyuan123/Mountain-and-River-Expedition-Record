package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.entity.Bandit;
import com.wargame.service.WorldContentService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorldContentServiceTest extends BaseServiceTest {
    @Autowired WorldContentService content;
    @Autowired WorldTerrainService terrain;

    @Test void backfillsDenseResourcesAndSeaFleetsOnceWithoutResettingExistingTargets() {
        var world = createTestWorld();
        var player = createTestPlayer("content-player", 30);
        player.setCityPosX(90); player.setCityPosY(90); player.setGameInitialized(true);
        playerRepository.save(player);
        var claimed = createWildTile(world.getId(), "oil", 92, 90, 3, Map.of(), 800);
        claimed.setOccupied(true); claimed.setOccupiedBy(player.getId());
        wildTileRepository.save(claimed);
        Bandit defeated = new Bandit();
        defeated.setWorldId(world.getId()); defeated.setName("旧日寇据点");
        defeated.setX(94); defeated.setY(90); defeated.setLevel(2);
        defeated.setArmy(JsonUtil.toJson(Map.of("infantry", 10))); defeated.setDefeated(true);
        banditRepository.save(defeated);
        final Long defeatedId = defeated.getId();
        String mask = terrain.ensure();

        content.ensure(world.getId());
        var allWilds = wildTileRepository.findByWorldId(world.getId());
        var allNpcs = banditRepository.findByWorldId(world.getId());
        assertTrue(allWilds.size() > 200);
        assertTrue(allNpcs.size() > 100);
        assertTrue(allWilds.stream().filter(w -> Math.abs(w.getX() - 90) + Math.abs(w.getY() - 90) <= 7).count() >= 8);
        assertTrue(allWilds.stream().filter(w -> Math.abs(w.getX() - 90) + Math.abs(w.getY() - 90) <= 3).count() >= 4);
        assertTrue(allWilds.stream().map(w -> w.getType()).collect(Collectors.toSet())
                .containsAll(Set.of("grainfield", "ironworks", "oil", "rarefactory")));
        assertTrue(wildTileRepository.findById(claimed.getId()).orElseThrow().getOccupied());
        assertEquals(player.getId(), wildTileRepository.findById(claimed.getId()).orElseThrow().getOccupiedBy());
        assertTrue(banditRepository.findById(defeatedId).orElseThrow().getDefeated());

        var fleets = allNpcs.stream().filter(b -> WorldTerrainService.sea(mask, b.getX(), b.getY())).toList();
        assertTrue(fleets.size() >= 20);
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("舰队")));
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("航母编队")));
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("潜艇支队")));
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("驱逐舰队")));
        fleets.forEach(fleet -> {
            assertTrue(fleet.getName().startsWith("日寇"));
            assertTrue(JsonUtil.parseIntMap(fleet.getArmy()).keySet().stream()
                    .allMatch(unit -> Set.of("sea", "air").contains(GameData.UNITS.get(unit).branch())));
        });
        assertTrue(allWilds.stream().noneMatch(w -> WorldTerrainService.sea(mask, w.getX(), w.getY())));
        assertEquals(2, worldMapRepository.findById(world.getId()).orElseThrow().getWorldContentVersion());
        content.ensure(world.getId());
        assertEquals(allWilds.size(), wildTileRepository.findByWorldId(world.getId()).size());
        assertEquals(allNpcs.size(), banditRepository.findByWorldId(world.getId()).size());
    }

    @Test void upgradesVersionOneWithSmallVesselsWithoutRespawningDefeatedFleets() {
        var world = createTestWorld();
        world.setWorldContentVersion(1);
        worldMapRepository.save(world);
        String mask = terrain.ensure();
        int cell = mask.indexOf('1');
        Bandit defeated = new Bandit();
        defeated.setWorldId(world.getId()); defeated.setName("日寇第1舰队");
        defeated.setX(cell % WorldTerrainService.SIZE); defeated.setY(cell / WorldTerrainService.SIZE);
        defeated.setLevel(3); defeated.setArmy(JsonUtil.toJson(Map.of("battleship", 1)));
        defeated.setDefeated(true);
        banditRepository.save(defeated);
        final Long defeatedId = defeated.getId();

        content.ensure(world.getId());
        var fleets = banditRepository.findByWorldId(world.getId());
        assertTrue(fleets.size() > 20);
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("潜艇支队")));
        assertTrue(fleets.stream().anyMatch(b -> b.getName().contains("驱逐舰队")));
        assertTrue(fleets.stream().filter(b -> !b.getId().equals(defeatedId))
                .allMatch(b -> WorldTerrainService.sea(mask, b.getX(), b.getY())));
        assertTrue(wildTileRepository.findByWorldId(world.getId()).isEmpty());
        assertTrue(banditRepository.findById(defeatedId).orElseThrow().getDefeated());
        assertEquals(2, worldMapRepository.findById(world.getId()).orElseThrow().getWorldContentVersion());
        content.ensure(world.getId());
        assertEquals(fleets.size(), banditRepository.findByWorldId(world.getId()).size());
    }
}
