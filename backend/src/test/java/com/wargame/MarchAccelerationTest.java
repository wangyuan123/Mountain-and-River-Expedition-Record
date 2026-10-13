package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.WebSocketPushService;
import com.wargame.service.WorldViewService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class MarchAccelerationTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @Autowired WorldViewService views;
    @SpyBean WebSocketPushService pushes;
    private Long playerId;
    private WildTile tile;

    @BeforeEach void setup() {
        playerId = createTestPlayer("march-acceleration", 30).getId();
        items.save(new PlayerItem(null, playerId, "marchOrd", 3, System.currentTimeMillis()));
        tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 0);
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        tile.setTotalRes(10_000);
        wildTileRepository.save(tile);
    }

    private March moving(boolean returning) {
        long now = System.currentTimeMillis();
        return createMarch(playerId, "wild", tile.getId().toString(), "粮田", 10, 10, 15, 15,
                Map.of("truck", 2), "station", now - 120_000, now + 180_000, returning, false);
    }

    private March accelerate(March march) {
        marchRepository.flush();
        marchService.speedUpMarch(playerId, march.getId(), march.getReturning(), march.getVersion());
        return marchRepository.findById(march.getId()).orElseThrow();
    }

    private int stock() { return items.findByPlayerIdAndItemKey(playerId, "marchOrd").orElseThrow().getCount(); }

    @Test void consumesOneOrderAndPreservesPositionAndUnpaidBaseDuration() {
        March old = moving(false);
        long start = old.getStartAt(), arrival = old.getArriveAt(), version = old.getVersion();
        March updated = accelerate(old);
        long saved = arrival - updated.getArriveAt();
        long operationTime = arrival - saved * 3;
        assertTrue(saved > 59_000 && saved <= 60_000);
        double before = (double) (operationTime - start) / (arrival - start);
        double after = (double) (operationTime - updated.getStartAt()) / (updated.getArriveAt() - updated.getStartAt());
        assertEquals(before, after, 0.00002, "压缩时间轴不能让地图队伍跳跃");
        assertEquals(300_000L, updated.getBaseDurationMs());
        assertEquals(2, stock());
        assertTrue(updated.getOutboundAccelerated());
        assertFalse(updated.getReturnAccelerated());
        assertTrue(updated.getVersion() > version);
        assertEquals(old.getTargetX(), updated.getTargetX());
        assertEquals(old.getArmy(), updated.getArmy());
        var view = views.toMarchMap(updated, System.currentTimeMillis());
        assertEquals(updated.getVersion(), view.get("version"));
        assertEquals(true, view.get("outboundAccelerated"));
        verify(pushes).pushMarchUpdate(eq(playerId), argThat(event -> "accelerated".equals(event.get("event"))));
    }

    @Test void tenSecondFloorAppliesNearArrival() {
        March march = moving(false);
        march.setArriveAt(System.currentTimeMillis() + 14_000);
        long before = System.currentTimeMillis();
        March updated = accelerate(march);
        long after = System.currentTimeMillis();
        assertTrue(updated.getArriveAt() >= before + 10_000 && updated.getArriveAt() <= after + 10_000);
        assertEquals(2, stock());
    }

    @ParameterizedTest @ValueSource(strings = {"battle", "stationed", "gathering", "stopped", "arrived", "tenSeconds", "futureStart"})
    void rejectsNonMovingOrLowBenefitStatesWithoutConsuming(String state) {
        March march = moving(false);
        switch (state) {
            case "battle" -> march.setBattleId(7L);
            case "stationed" -> march.setStationed(true);
            case "gathering" -> march.setGathering(true);
            case "stopped" -> march.setGatherStopped(true);
            case "arrived" -> march.setArriveAt(System.currentTimeMillis() - 1);
            case "tenSeconds" -> march.setArriveAt(System.currentTimeMillis() + 10_000);
            case "futureStart" -> march.setStartAt(System.currentTimeMillis() + 20_000);
        }
        marchRepository.saveAndFlush(march);
        assertThrows(IllegalArgumentException.class, () ->
                marchService.speedUpMarch(playerId, march.getId(), false, march.getVersion()));
        assertEquals(3, stock());
        assertFalse(marchRepository.findById(march.getId()).orElseThrow().getOutboundAccelerated());
    }

    @ParameterizedTest @ValueSource(strings = {"stage", "version", "owner", "city", "missingStage", "missingVersion", "noStock"})
    void rejectsStaleUnauthorizedAndInsufficientStockRequests(String reason) {
        March march = moving(false);
        if ("city".equals(reason)) march.setCitySlot(1);
        if ("owner".equals(reason)) march.setPlayerId(createTestPlayer("other-march-owner", 30).getId());
        if ("noStock".equals(reason)) {
            var item = items.findByPlayerIdAndItemKey(playerId, "marchOrd").orElseThrow();
            item.setCount(0); items.save(item);
        }
        marchRepository.saveAndFlush(march);
        long arrival = march.getArriveAt();
        Boolean stage = "missingStage".equals(reason) ? null : "stage".equals(reason);
        Long version = "missingVersion".equals(reason) ? null : march.getVersion() + ("version".equals(reason) ? 1 : 0);
        assertThrows(IllegalArgumentException.class, () -> marchService.speedUpMarch(playerId, march.getId(), stage, version));
        assertEquals("noStock".equals(reason) ? 0 : 3, stock());
        assertEquals(arrival, marchRepository.findById(march.getId()).orElseThrow().getArriveAt());
    }

    @Test void outboundAndReturnLegsCanEachAccelerateOnce() {
        March accelerated = accelerate(moving(false));
        marchService.cancelMarch(playerId, accelerated.getId());
        March returning = marchRepository.findById(accelerated.getId()).orElseThrow();
        assertTrue(returning.getReturning());
        assertFalse(returning.getReturnAccelerated());
        March fastReturn = accelerate(returning);
        assertTrue(fastReturn.getReturnAccelerated());
        assertEquals(1, stock());
        assertThrows(IllegalArgumentException.class, () ->
                marchService.speedUpMarch(playerId, fastReturn.getId(), true, fastReturn.getVersion()));
        assertEquals(1, stock());
    }

    @Test void repeatedOutboundRequestCannotConsumeAgainEvenWithFreshVersion() {
        March updated = accelerate(moving(false));
        assertThrows(IllegalArgumentException.class, () ->
                marchService.speedUpMarch(playerId, updated.getId(), false, updated.getVersion()));
        assertEquals(2, stock());
    }

    @Test void cancellationUsesTravelledProportionAtUnpaidReturnSpeed() {
        March outbound = accelerate(moving(false));
        long before = System.currentTimeMillis();
        double progress = (double) (before - outbound.getStartAt()) / (outbound.getArriveAt() - outbound.getStartAt());
        marchService.cancelMarch(playerId, outbound.getId());
        March returning = marchRepository.findById(outbound.getId()).orElseThrow();
        long after = System.currentTimeMillis();
        assertEquals(300_000L, returning.getArriveAt() - returning.getStartAt());
        assertEquals(progress * 300_000, returning.getArriveAt() - before, (after - before) * 3 + 3);
        double returnProgress = (double) (before - returning.getStartAt()) / 300_000;
        assertEquals(1 - progress, returnProgress, (after - before + 3) / 100_000.0);
        assertEquals(10, returning.getTargetX());
    }

    @Test void stationAndRecallRetainUnpaidDuration() {
        March outbound = accelerate(moving(false));
        marchService.processMarches(playerId, outbound.getArriveAt());
        assertTrue(marchRepository.findById(outbound.getId()).isEmpty());
        var routes = JsonUtil.parseList(wildTileRepository.findById(tile.getId()).orElseThrow().getGarrisonRoutes());
        assertEquals(300_000L, ((Number) routes.get(0).get("duration")).longValue());
        marchService.recallWild(playerId, tile.getId());
        March returning = marchRepository.findByPlayerId(playerId).get(0);
        assertTrue(returning.getReturning());
        assertEquals(300_000L, returning.getArriveAt() - returning.getStartAt());
        assertFalse(returning.getReturnAccelerated());
    }

    @Test void automaticBattleReturnKeepsUnpaidDuration() {
        tile = wildTileRepository.findById(tile.getId()).orElseThrow();
        tile.setOccupied(false); tile.setOccupiedBy(null); wildTileRepository.save(tile);
        March march = moving(false);
        march.setAction("conquer"); march.setArmy("{\"infantry\":100}"); march.setBattleMode("auto");
        March outbound = accelerate(march);
        marchService.processMarches(playerId, outbound.getArriveAt());
        March returning = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(returning.getReturning());
        assertEquals(300_000L, returning.getArriveAt() - returning.getStartAt());
        assertFalse(returning.getReturnAccelerated());
    }

    @ParameterizedTest @ValueSource(strings = {"auto", "manual"})
    void gatheringReturnDoesNotInheritOutboundAcceleration(String mode) {
        March march = moving(false);
        march.setTargetKind("wild_gather"); march.setAction("gather"); march.setGatherMode(mode);
        March accelerated = accelerate(march);
        long arrival = accelerated.getArriveAt();
        marchService.processMarches(playerId, arrival);
        March gathering = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(gathering.getGathering());
        if ("manual".equals(mode)) {
            marchService.stopGatherMarch(playerId, gathering.getId());
            marchService.cancelMarch(playerId, gathering.getId());
        } else marchService.processMarches(playerId, gathering.getGatherEndAt());
        March returning = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(returning.getReturning());
        assertEquals(300_000L, returning.getArriveAt() - returning.getStartAt());
        assertFalse(returning.getReturnAccelerated());
    }

    @Test void acceleratedReturnSettlesTroopsAndCargoOnlyOnce() {
        March march = moving(true);
        march.setGatherStopped(true);
        march.setCarryRes("{\"food\":120}");
        int food = getResources(playerId).getFood();
        March returning = accelerate(march);
        marchService.processMarches(playerId, returning.getArriveAt());
        marchService.processMarches(playerId, returning.getArriveAt() + 1);
        assertTrue(marchRepository.findById(march.getId()).isEmpty());
        assertEquals(2, armyUnitRepository.findByPlayerIdAndType(playerId, "truck").get(0).getCount());
        assertEquals(food + 120, getResources(playerId).getFood());
    }

    @Test void realDefenderReceivesNewIncomingArrival() {
        Long defenderId = createTestPlayer("speedup-defender", 30).getId();
        PlayerCity city = new PlayerCity();
        city.setOwnerId(defenderId); city.setWorldId(tile.getWorldId());
        city.setName("守方城市"); city.setX(15); city.setY(15);
        city.setArmy("{}"); city.setForts("{}"); city.setResources("{}");
        playerCityRepository.save(city);
        March march = moving(false);
        march.setTargetKind("player"); march.setTargetId(city.getId().toString()); march.setAction("plunder");
        March updated = accelerate(march);
        verify(pushes).pushIncomingAttack(eq(defenderId), argThat(event ->
                "accelerated".equals(event.get("event")) && updated.getArriveAt().equals(event.get("arriveAt"))));
        assertEquals(updated.getArriveAt(), views.getIncoming(playerRepository.findById(defenderId).orElseThrow()).get(0).get("arriveAt"));
    }
}
