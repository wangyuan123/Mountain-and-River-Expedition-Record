package com.wargame;

import com.wargame.model.entity.Construction;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class FreeBuildSpeedUpTest extends BaseServiceTest {

    @Autowired private PlayerItemRepository items;

    @Test
    void completesAtFiveMinuteBoundaryWithoutCostAndSettlesOnlyOnce() {
        Long playerId = createTestPlayer().getId();
        long now = System.currentTimeMillis();
        Construction job = createConstruction(playerId, "command", 1, now - 1000, now + 300000, null);
        Construction other = createConstruction(playerId, "farm", 1, now, now + 240000, 0);
        PlayerItem item = new PlayerItem();
        item.setPlayerId(playerId);
        item.setItemKey("speedUp10m");
        item.setCount(3);
        item.setUpdatedAt(now);
        items.save(item);
        var before = getResources(playerId);
        var balances = java.util.List.of(before.getFood(), before.getSteel(), before.getOil(), before.getRare(), before.getGold());

        assertEquals(true, buildService.freeSpeedUp(playerId, job.getId()).get("success"));
        assertFalse(constructionRepository.existsById(job.getId()));
        assertTrue(constructionRepository.existsById(other.getId()));
        assertEquals(1, buildingRepository.findByPlayerIdAndType(playerId, "command").get(0).getLevel());
        int prestige = playerRepository.findById(playerId).orElseThrow().getPrestige();
        assertTrue(prestige > 0);
        assertEquals(3, items.findByPlayerIdAndItemKey(playerId, "speedUp10m").orElseThrow().getCount());
        var after = getResources(playerId);
        assertEquals(balances, java.util.List.of(after.getFood(), after.getSteel(), after.getOil(), after.getRare(), after.getGold()));

        assertEquals(false, buildService.freeSpeedUp(playerId, job.getId()).get("success"));
        assertEquals(prestige, playerRepository.findById(playerId).orElseThrow().getPrestige());
        assertTrue(constructionRepository.existsById(other.getId()));
    }

    @Test
    void rejectsLongJobsAndInvalidTargetsWithoutChangingQueue() {
        Long playerId = createTestPlayer().getId();
        long now = System.currentTimeMillis();
        Construction job = createConstruction(playerId, "farm", 1, now, now + 360000, 0);
        assertEquals(false, buildService.freeSpeedUp(playerId, job.getId()).get("success"));
        assertEquals(now + 360000, constructionRepository.findById(job.getId()).orElseThrow().getFinishAt());
        assertEquals(false, buildService.freeSpeedUp(playerId, null).get("success"));
        assertEquals(false, buildService.freeSpeedUp(playerId, -1L).get("success"));
        assertTrue(buildingRepository.findByPlayerIdAndType(playerId, "farm").isEmpty());
    }

    @Test
    void rejectsAnotherPlayerOrCityQueue() {
        Long playerId = createTestPlayer().getId();
        Long otherPlayer = createTestPlayer("other-builder", 30).getId();
        long now = System.currentTimeMillis();
        Construction foreign = createConstruction(otherPlayer, "farm", 1, now, now + 60000, 0);
        Construction otherCity = createConstruction(playerId, "farm", 1, now, now + 60000, 0);
        otherCity.setCitySlot(1);
        constructionRepository.save(otherCity);
        assertEquals(false, buildService.freeSpeedUp(playerId, foreign.getId()).get("success"));
        assertEquals(false, buildService.freeSpeedUp(playerId, otherCity.getId()).get("success"));
        assertTrue(constructionRepository.existsById(foreign.getId()));
        assertTrue(constructionRepository.existsById(otherCity.getId()));
    }

    @Test
    void completesDismantlingAndExpiredConstructionWithoutItems() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "farm", 1);
        assertEquals(true, buildService.dismantle(playerId, "farm", 0).get("success"));
        Construction job = constructionRepository.findByPlayerId(playerId).get(0);
        assertEquals(true, buildService.freeSpeedUp(playerId, job.getId()).get("success"));
        assertTrue(buildingRepository.findByPlayerIdAndType(playerId, "farm").isEmpty());
        long now = System.currentTimeMillis();
        Construction expired = createConstruction(playerId, "command", 1, now - 60000, now - 1000, null);
        assertEquals(true, buildService.freeSpeedUp(playerId, expired.getId()).get("success"));
        assertTrue(constructionRepository.findByPlayerId(playerId).isEmpty());
        assertTrue(items.findByPlayerId(playerId).isEmpty());
    }
}
