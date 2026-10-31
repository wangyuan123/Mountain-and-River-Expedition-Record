package com.wargame;

import com.wargame.model.constants.TechnologyRules;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.entity.March;
import com.wargame.service.MarchService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/** 验证战后装载边界，避免初始兵力、携带物资或整数溢出绕过负重。 */
class MarchLootCapacityTest {
    private final MarchService service = mock(MarchService.class, CALLS_REAL_METHODS);

    private BattleResult settle(Map<String, Integer> survivors, Map<String, Integer> loot,
                                Map<String, Integer> equipment, Map<String, Integer> carried) {
        March march = new March();
        march.setArmy(JsonUtil.toJson(Map.of("truck", 100)));
        march.setActionSnapshot(JsonUtil.toJson(equipment));
        march.setCarryRes(JsonUtil.toJson(carried));
        BattleResult result = new BattleResult();
        result.setWin(true);
        result.setSurvivorAttacker(survivors);
        result.setPlunderedResources(loot);
        result.setReport("★ 征服胜利! 夺取敌方全部资源。");
        ReflectionTestUtils.invokeMethod(service, "limitBattleLoot", march, result);
        return result;
    }

    @Test void survivorsShareOneCapacityAcrossAllOrdinaryResources() {
        BattleResult result = settle(Map.of("infantry", 100, "truck", 1),
                Map.of("food", 1000, "steel", 1000, "oil", 1000, "rare", 1000, "gold", 1000), Map.of(), Map.of());
        assertEquals(Map.of("food", 300, "steel", 300, "oil", 300, "rare", 300, "gold", 300), result.getPlunderedResources());
        assertFalse(result.getReport().contains("夺取敌方全部资源"));
        assertTrue(result.getReport().contains("实际装载 1500"));
    }

    @Test void survivingInfantryCanLootWithoutTrucksAndLossesReduceCapacity() {
        var result = settle(Map.of("infantry", 17), Map.of("food", 10000), Map.of(), Map.of("steel", 20));
        assertEquals(150, result.getPlunderedResources().get("food"));
        assertEquals(10, service.calcArmyLoad(Map.of("infantry", 1)));
        assertEquals(service.calcArmyLoad(Map.of("truck", 1)), service.calcArmyLoad(Map.of("infantry", 50)));
    }

    @Test void infantryLoadUsesFrozenTechnologyAndFloorsAfterSumming() {
        var frozen = TechnologyRules.actionSnapshot(Map.of("log_load", 10));
        assertEquals(15, service.calcArmyLoad(Map.of("infantry", 1), frozen));
        assertEquals(105, service.calcArmyLoad(Map.of("infantry", 10), TechnologyRules.actionSnapshot(Map.of("log_load", 1))));
        var result = settle(Map.of("infantry", 10), Map.of("food", 10000), frozen, Map.of());
        assertEquals(150, result.getPlunderedResources().get("food"));
    }

    @Test void frozenEquipmentAndTechnologyApplyAfterReservingExistingCargo() {
        var equipment = Map.of(TechnologyRules.LOAD_SNAPSHOT, 2, "@ue:" + "truck" + ":" + "load", 1000);
        int remaining = service.calcArmyLoad(Map.of("truck", 1), equipment) - 50;
        var result = settle(Map.of("truck", 1), Map.of("steel", 10000, "diamond", 20), equipment, Map.of("food", 50));
        assertEquals(remaining, result.getPlunderedResources().get("steel"));
        assertEquals(20, result.getPlunderedResources().get("diamond"));
    }

    @Test void noSurvivingLoadBearingUnitMeansNoOrdinaryLoot() {
        for (var army : List.of(Map.of("ltank", 1000), Map.<String, Integer>of())) {
            var result = settle(army, Map.of("food", 10000, "gold", 1000, "diamond", 20), Map.of(), Map.of());
            assertEquals(0, result.getPlunderedResources().get("food"));
            assertEquals(0, result.getPlunderedResources().get("gold"));
            assertEquals(20, result.getPlunderedResources().get("diamond"));
        }
    }

    @Test void fullOrOverloadedExistingCargoLeavesNoRoomForLoot() {
        for (int carried : new int[]{500, 600, Integer.MAX_VALUE}) {
            var result = settle(Map.of("truck", 1), Map.of("steel", 10000), Map.of(), Map.of("food", carried));
            assertEquals(0, result.getPlunderedResources().get("steel"));
        }
    }

    @Test void lootBelowCapacityIsUnchanged() {
        var loot = Map.of("food", 30, "steel", 200, "gold", 50);
        assertEquals(loot, settle(Map.of("truck", 1), loot, Map.of(), Map.of("oil", 100)).getPlunderedResources());
    }

    @Test void roundingFillsCapacityWithoutTakingUnavailableResources() {
        var result = settle(Map.of("truck", 1), Map.of("food", 1, "steel", 1000, "gold", 1000), Map.of(), Map.of());
        assertEquals(500, result.getPlunderedResources().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, result.getPlunderedResources().get("food"));
        assertEquals(250, result.getPlunderedResources().get("steel"));
        assertEquals(250, result.getPlunderedResources().get("gold"));
    }

    @Test void combinedStockBeyondIntegerRangeStillRespectsCapacity() {
        var loot = Map.of("food", Integer.MAX_VALUE, "steel", Integer.MAX_VALUE, "oil", Integer.MAX_VALUE,
                "rare", Integer.MAX_VALUE, "gold", Integer.MAX_VALUE);
        var result = settle(Map.of("truck", 1), loot, Map.of(), Map.of());
        assertEquals(500, result.getPlunderedResources().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(result.getPlunderedResources().values().stream().allMatch(amount -> amount == 100));
    }
}
