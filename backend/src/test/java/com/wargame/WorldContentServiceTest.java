package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.WorldConfig;
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
        assertTrue(fleets.stream().allMatch(b -> b.getName().startsWith("日寇海域守军 Lv.")));
        assertTrue(fleets.stream().map(Bandit::getLevel).collect(Collectors.toSet()).containsAll(
                java.util.stream.IntStream.rangeClosed(1, 30).boxed().collect(Collectors.toSet())));
        fleets.forEach(fleet -> {
            assertTrue(fleet.getName().startsWith("日寇"));
            assertEquals(com.wargame.model.constants.WorldConfig.seaNpcGarrison(fleet.getLevel()),
                    JsonUtil.parseIntMap(fleet.getArmy()));
            assertTrue(JsonUtil.parseIntMap(fleet.getArmy()).keySet().stream()
                    .allMatch(unit -> Set.of("sea", "air").contains(GameData.UNITS.get(unit).branch())));
        });
        assertTrue(allWilds.stream().noneMatch(w -> WorldTerrainService.sea(mask, w.getX(), w.getY())));
        assertEquals(3, worldMapRepository.findById(world.getId()).orElseThrow().getWorldContentVersion());
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
        assertTrue(fleets.stream().filter(b -> !b.getId().equals(defeatedId))
                .allMatch(b -> b.getName().startsWith("日寇海域守军 Lv.")));
        assertTrue(fleets.stream().filter(b -> !b.getId().equals(defeatedId))
                .allMatch(b -> WorldTerrainService.sea(mask, b.getX(), b.getY())));
        assertTrue(wildTileRepository.findByWorldId(world.getId()).isEmpty());
        assertTrue(banditRepository.findById(defeatedId).orElseThrow().getDefeated());
        assertEquals(Map.of("battleship", 1), JsonUtil.parseIntMap(banditRepository.findById(defeatedId).orElseThrow().getArmy()));
        assertEquals(3, worldMapRepository.findById(world.getId()).orElseThrow().getWorldContentVersion());
        content.ensure(world.getId());
        assertEquals(fleets.size(), banditRepository.findByWorldId(world.getId()).size());
    }

    @Test void upgradesVersionTwoWithoutAddingTargetsOrRevivingDefeatedFleets() {
        var world = createTestWorld();
        world.setWorldContentVersion(2);
        worldMapRepository.save(world);
        String mask = terrain.ensure();
        int cell = mask.indexOf('1');
        Bandit active = new Bandit();
        active.setWorldId(world.getId()); active.setName("日寇第4航母编队");
        active.setX(cell % WorldTerrainService.SIZE); active.setY(cell / WorldTerrainService.SIZE);
        active.setLevel(6); active.setArmy(JsonUtil.toJson(Map.of("carrier", 1)));
        active.setDefeated(false); banditRepository.save(active);
        int nextCell = mask.indexOf('1', cell + 1);
        Bandit defeated = new Bandit();
        defeated.setWorldId(world.getId()); defeated.setName("日寇第5潜艇支队");
        defeated.setX(nextCell % WorldTerrainService.SIZE); defeated.setY(nextCell / WorldTerrainService.SIZE);
        defeated.setLevel(3); defeated.setArmy("{}"); defeated.setDefeated(true);
        banditRepository.save(defeated);

        content.ensure(world.getId());
        assertEquals(2, banditRepository.findByWorldId(world.getId()).size());
        assertEquals(WorldConfig.seaNpcGarrison(1), JsonUtil.parseIntMap(banditRepository.findById(active.getId()).orElseThrow().getArmy()));
        assertTrue(banditRepository.findById(defeated.getId()).orElseThrow().getDefeated());
        assertEquals(Map.of(), JsonUtil.parseIntMap(banditRepository.findById(defeated.getId()).orElseThrow().getArmy()));
        assertEquals(3, worldMapRepository.findById(world.getId()).orElseThrow().getWorldContentVersion());
    }
}
