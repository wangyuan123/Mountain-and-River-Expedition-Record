package com.wargame;

import com.wargame.model.entity.ArmyProductionQueue;
import com.wargame.model.entity.ArmyUnit;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.ArmyUnitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class FreeArmySpeedUpTest extends BaseServiceTest {

    @Autowired private com.wargame.service.ArmyService armyService;
    @Autowired private ArmyProductionQueueRepository queueRepository;
    @Autowired private ArmyUnitRepository unitRepository;

    @Test
    void completesWithinFiveMinutesAndRecruitsUnits() {
        Long playerId = createTestPlayer().getId();
        long now = System.currentTimeMillis();

        ArmyProductionQueue queue = new ArmyProductionQueue();
        queue.setPlayerId(playerId);
        queue.setCitySlot(0);
        queue.setUnitType("infantry");
        queue.setUnitCount(50);
        queue.setStartedAt(now - 1000);
        queue.setFinishesAt(now + 250000);
        queue.setDurationSeconds(250);
        queue.setSpeedMultiplier(1.0);
        queue.setCostFood(100);
        queue.setCostSteel(50);
        queue.setCostOil(0);
        queue.setCostRare(0);
        queueRepository.save(queue);

        var res = armyService.freeSpeedUp(playerId, queue.getId());
        assertEquals(true, res.get("success"));
        assertFalse(queueRepository.existsById(queue.getId()));

        var units = unitRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "infantry");
        assertFalse(units.isEmpty());
        assertEquals(50, units.get(0).getCount());

        // 重复点击返回失败
        var resRepeat = armyService.freeSpeedUp(playerId, queue.getId());
        assertEquals(false, resRepeat.get("success"));
    }

    @Test
    void rejectsOverFiveMinutes() {
        Long playerId = createTestPlayer().getId();
        long now = System.currentTimeMillis();

        ArmyProductionQueue queue = new ArmyProductionQueue();
        queue.setPlayerId(playerId);
        queue.setCitySlot(0);
        queue.setUnitType("tank");
        queue.setUnitCount(10);
        queue.setStartedAt(now);
        queue.setFinishesAt(now + 600000);
        queue.setDurationSeconds(600);
        queue.setSpeedMultiplier(1.0);
        queueRepository.save(queue);

        var res = armyService.freeSpeedUp(playerId, queue.getId());
        assertEquals(false, res.get("success"));
        assertTrue(queueRepository.existsById(queue.getId()));
    }
}
