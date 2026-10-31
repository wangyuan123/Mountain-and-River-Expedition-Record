package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.March;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.MailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 自动采集取消后的回归：旧指令不能继续放弃领地，手动结算仍须幂等。 */
class GatherAutoAbandonTest extends BaseServiceTest {
    @Autowired private MailRepository mails;
    private Long playerId;
    private WildTile tile;

    @BeforeEach
    void setUp() {
        playerId = createTestPlayer("manual-gather-player", 30).getId();
        tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 700);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        wildTileRepository.saveAndFlush(tile);
        createArmyUnit(playerId, "truck", 10);
    }

    @Test
    void removedAutomaticInstructionsAreRejectedBeforeDeductingTroopsOrCreatingTasks() {
        for (String mode : new String[]{"auto", "invalid"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> marchService.createDispatch(playerId, request(mode, "retain")));
        }
        for (String mode : new String[]{null, "manual", "auto"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> marchService.createDispatch(playerId, request(mode, "abandon")));
        }
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(1000, getResources(playerId).getOil());
        assertTrue(tile.getOccupied());
    }

    @Test
    void legacyPendingAutomaticAbandonNeverRunsEvenAfterManualHarvestAndReturn() {
        March march = beginGather();
        march.setGatherMode("auto");
        march.setGatherAfterAction("abandon");
        march.setGatherAfterActionResult("pending");
        marchRepository.save(march);
        marchService.processMarches(playerId, System.currentTimeMillis() + 86_400_000L);
        assertTrue(march.getGathering());
        assertFalse(march.getReturning());
        assertEquals(700, tile.getTotalRes());
        marchService.stopGatherMarch(playerId, march.getId());
        assertFalse(march.getReturning());
        marchService.processMarches(playerId, System.currentTimeMillis() + 86_400_000L);
        assertFalse(march.getReturning());
        marchService.cancelMarch(playerId, march.getId());
        marchService.processMarches(playerId, march.getArriveAt());
        assertEquals(1500, getResources(playerId).getFood());
        assertTrue(tile.getOccupied());
        assertEquals(200, tile.getTotalRes());
        assertEquals(0, mails.count());
        assertEquals(true, worldService.abandonWild(playerId, tile.getId()).get("success"));
        assertFalse(tile.getOccupied());
    }

    @Test
    void multipleManualHarvestsCannotOverdrawOrCreditTheSameResourcesTwice() {
        March first = beginGather();
        March second = beginGather();
        marchService.stopGatherMarch(playerId, first.getId());
        marchService.stopGatherMarch(playerId, second.getId());
        marchService.stopGatherMarch(playerId, first.getId());
        assertEquals(500, first.getGatherAmount());
        assertEquals(200, second.getGatherAmount());
        assertEquals(0, tile.getTotalRes());
        assertTrue(tile.getOccupied());
        assertEquals(false, worldService.abandonWild(playerId, tile.getId()).get("success"));
        marchService.cancelMarch(playerId, first.getId());
        marchService.cancelMarch(playerId, second.getId());
        long returnedAt = Math.max(first.getArriveAt(), second.getArriveAt());
        marchService.processMarches(playerId, returnedAt);
        marchService.processMarches(playerId, returnedAt + 1000);
        assertEquals(1700, getResources(playerId).getFood());
        assertEquals(10, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(0, tile.getTotalRes());
        assertTrue(tile.getOccupied());
    }

    private March beginGather() {
        March march = marchService.createDispatch(playerId,
                new DispatchRequest("wild", tile.getId(), "station", Map.of("truck", 1), null, null));
        // 模拟升级前已经开始的采集存档，保留手动收获及并发结算的兼容性验证。
        march.setTargetKind("wild_gather");
        march.setAction("gather");
        march.setGathering(true);
        march.setGatherAmount(500);
        march.setGatherRes("food");
        march.setGatherOccupationVersion(tile.getOccupationVersion());
        march.setGatherOwnerCitySlot(tile.getOccupiedCitySlot());
        march.setGatherStartAt(System.currentTimeMillis() - 120_000L);
        march.setGatherEndAt(System.currentTimeMillis() - 60_000L);
        marchRepository.save(march);
        return march;
    }

    private DispatchRequest request(String mode, String afterAction) {
        return new DispatchRequest("wild_gather", tile.getId(), "gather", Map.of("truck", 1),
                null, null, mode, null, null, null, null, null, null, afterAction);
    }
}
