package com.wargame;

import com.wargame.model.entity.ArmyProductionQueue;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ArmyService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class ProductionAccelerationTest extends BaseServiceTest {
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired PlayerItemRepository items;

    @Test
    void freeCompletionBringsWaitingOrdersForwardWithoutChangingTheirWork() {
        Long player = createTestPlayer("production-speed", 30).getId();
        createBuilding(player, "barracks", 3);
        long now = GameClock.now();
        ArmyProductionQueue first = order(player, "infantry", now - 1000, now + 240000);
        ArmyProductionQueue second = order(player, "truck", first.getFinishesAt(), first.getFinishesAt() + 60000);
        ArmyProductionQueue third = order(player, "recon_car", second.getFinishesAt(), second.getFinishesAt() + 30000);
        assertEquals(true, army.freeSpeedUp(player, first.getId()).get("success"));
        assertTrue(second.getStartedAt() < now + 1000, "后单应立即开工");
        assertEquals(60000, second.getFinishesAt() - second.getStartedAt());
        assertEquals(second.getFinishesAt(), third.getStartedAt());
        assertEquals(30000, third.getFinishesAt() - third.getStartedAt());
    }

    @Test
    void paidPartialAccelerationMovesSuccessorsByExactlyTheSavedTime() {
        Long player = createTestPlayer("paid-speed", 30).getId();
        createBuilding(player, "barracks", 3);
        long now = GameClock.now();
        ArmyProductionQueue first = order(player, "infantry", now - 1000, now + 3600000);
        ArmyProductionQueue second = order(player, "truck", first.getFinishesAt(), first.getFinishesAt() + 60000);
        long oldStart = second.getStartedAt();
        speedCard(player, "speedUp10m");
        assertEquals(true, army.useSpeedUp(player, "speedUp10m", first.getId()).get("success"));
        // 原子扣道具会清理JPA上下文，验证持久化队列而不是已脱管的旧对象。
        first = queues.findById(first.getId()).orElseThrow();
        second = queues.findById(second.getId()).orElseThrow();
        assertEquals(now + 3000000, first.getFinishesAt());
        assertEquals(oldStart - 600000, second.getStartedAt());
        assertEquals(60000, second.getFinishesAt() - second.getStartedAt());
        assertEquals(first.getFinishesAt(), second.getStartedAt());
    }

    @Test
    void surplusSpeedDoesNotCompleteTheNextOrderOrTouchAnotherCityOrFacility() {
        Long player = createTestPlayer("surplus-speed", 30).getId();
        createBuilding(player, "barracks", 3);
        long now = GameClock.now();
        ArmyProductionQueue first = order(player, "infantry", now - 1000, now + 10000);
        ArmyProductionQueue second = order(player, "truck", first.getFinishesAt(), first.getFinishesAt() + 60000);
        ArmyProductionQueue otherCity = order(player, "infantry", now + 10000, now + 70000);
        otherCity.setCitySlot(1); queues.save(otherCity);
        ArmyProductionQueue aircraft = order(player, "fighter", now + 10000, now + 90000);
        aircraft.setBuildingType("airport"); queues.save(aircraft);
        speedCard(player, "speedUp1h");
        assertEquals(true, army.useSpeedUp(player, "speedUp1h", first.getId()).get("success"));
        assertFalse(queues.existsById(first.getId()));
        assertTrue(queues.existsById(second.getId()));
        second = queues.findById(second.getId()).orElseThrow();
        assertEquals(60000, second.getFinishesAt() - second.getStartedAt());
        assertTrue(second.getStartedAt() < now + 1000);
        assertEquals(now + 10000, queues.findById(otherCity.getId()).orElseThrow().getStartedAt());
        assertEquals(now + 10000, queues.findById(aircraft.getId()).orElseThrow().getStartedAt());
    }

    @Test
    void parallelLaneKeepsRunningOrderAndItsSuccessorUnchanged() {
        Long player = createTestPlayer("parallel-speed", 30).getId();
        createBuilding(player, "barracks", 5);
        long now = GameClock.now();
        ArmyProductionQueue first = order(player, "infantry", now - 1000, now + 120000);
        ArmyProductionQueue running = order(player, "infantry", now - 1000, now + 240000);
        ArmyProductionQueue second = order(player, "truck", now + 120000, now + 180000);
        order(player, "recon_car", now + 180000, now + 360000);
        ArmyProductionQueue otherLane = order(player, "infantry", now + 240000, now + 300000);
        assertEquals(true, army.freeSpeedUp(player, first.getId()).get("success"));
        assertTrue(second.getStartedAt() < now + 1000);
        assertEquals(now + 240000, running.getFinishesAt());
        assertEquals(now - 1000, running.getStartedAt());
        assertEquals(now + 240000, otherLane.getStartedAt());
        assertEquals(now + 300000, otherLane.getFinishesAt());
    }

    private void speedCard(Long player, String key) {
        PlayerItem item = new PlayerItem();
        item.setPlayerId(player); item.setItemKey(key); item.setCount(1); item.setUpdatedAt(GameClock.now());
        items.save(item);
    }

    private ArmyProductionQueue order(Long player, String unit, long start, long finish) {
        ArmyProductionQueue q = new ArmyProductionQueue();
        q.setPlayerId(player); q.setCitySlot(0); q.setUnitType(unit); q.setBuildingType("barracks");
        q.setUnitCount(1); q.setStartedAt(start); q.setFinishesAt(finish);
        q.setDurationSeconds((int) ((finish - start) / 1000)); q.setSpeedMultiplier(1.0);
        return queues.save(q);
    }
}
