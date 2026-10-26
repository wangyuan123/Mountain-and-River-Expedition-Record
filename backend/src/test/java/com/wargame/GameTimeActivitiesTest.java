package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 游戏时间落后真实时间两天，验证业务入口确实不再用墙钟补结算。 */
class GameTimeActivitiesTest extends BaseServiceTest {
    @Autowired ArmyService army;
    @Autowired ArmyProductionQueueRepository production;
    @Autowired TechResearchQueueRepository research;
    @Autowired UnitEquipmentOrderRepository equipmentOrders;
    @Autowired UnitEquipmentService equipment;
    @Autowired BattleSessionRepository battles;

    @Test void cityQueuesAndEconomyStayFrozenUntilGameTimeReachesTheirDeadline() throws Exception {
        AtomicLong time = new AtomicLong(System.currentTimeMillis() - 2 * 86_400_000L);
        try (var ignored = GameClock.use(time::get)) {
            Player player = createTestPlayer("frozen-queues", 0);
            Long id = player.getId();
            player.setLastTick(time.get());
            createBuilding(id, "farm", 1);
            Construction building = createConstruction(id, "farm", 2, time.get(), time.get() + 60_000, 0);
            TechResearchQueue tech = new TechResearchQueue();
            tech.setPlayerId(id); tech.setCitySlot(0); tech.setTechType("log_food"); tech.setTargetLevel(1);
            tech.setStartedAt(time.get()); tech.setFinishesAt(time.get() + 60_000); tech.setDurationSeconds(60); research.save(tech);
            ArmyProductionQueue units = new ArmyProductionQueue();
            units.setPlayerId(id); units.setCitySlot(0); units.setUnitType("infantry"); units.setUnitCount(2);
            units.setBuildingType("factory"); units.setStartedAt(time.get()); units.setFinishesAt(time.get() + 60_000);
            units.setDurationSeconds(60); units.setSpeedMultiplier(1.0); production.save(units);
            UnitEquipmentOrder order = new UnitEquipmentOrder();
            order.setPlayerId(id); order.setCitySlot(0); order.setItemKey("ue_infantry_helmet"); order.setCost("{}");
            order.setStartAt(time.get()); order.setEndAt(time.get() + 60_000); equipmentOrders.save(order);
            long food = getResources(id).getFood();
            tickService.tick(id);
            tickService.tick(id);
            assertEquals(food, getResources(id).getFood());
            assertTrue(constructionRepository.existsById(building.getId()));
            assertTrue(research.existsById(tech.getId()));
            assertEquals(1, army.getProductionQueue(id).size());
            assertTrue(equipmentOrders.existsById(order.getId()));
            assertEquals(time.get(), gameStateService.getGameState(id).get("serverTime"));
            time.addAndGet(60_000);
            tickService.tick(id);
            assertFalse(constructionRepository.existsById(building.getId()));
            assertFalse(research.existsById(tech.getId()));
            assertTrue(production.findByPlayerIdOrderByStartedAtAscIdAsc(id).isEmpty());
            assertFalse(equipmentOrders.existsById(order.getId()));
            assertEquals(2, armyUnitRepository.findByPlayerIdAndType(id, "infantry").get(0).getCount());
            assertEquals(1, equipment.inventory(id).get("ue_infantry_helmet"));
        }
    }

    @Test void wildMarchAndTacticalRoundsContinueOnlyAfterRemainingGameTime() throws Exception {
        AtomicLong time = new AtomicLong(System.currentTimeMillis() - 2 * 86_400_000L);
        try (var ignored = GameClock.use(time::get)) {
            Player player = createTestPlayer("frozen-wild-battle", 0);
            Long id = player.getId(); player.setLastTick(time.get());
            createArmyUnit(id, "infantry", 10);
            var tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of("infantry", 10), 1000);
            March march = marchService.createDispatch(id, new DispatchRequest("wild", tile.getId(), "conquer",
                    Map.of("infantry", 10), null, null, null, "manual"));
            long arrival = march.getArriveAt();
            assertTrue(arrival > time.get());
            tickService.tick(id);
            assertTrue(battles.findByMarchId(march.getId()).isEmpty());
            time.set(arrival); tickService.tick(id);
            BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
            assertEquals(0, session.getRoundNo());
            assertEquals(arrival + 12_000, session.getRoundDeadlineAt());
            tickService.tick(id);
            assertEquals(0, session.getRoundNo());
            time.addAndGet(12_000);
            marchService.processTimedOutTacticalBattle(session.getId(), GameClock.now());
            // 野地回合锁会移除旧会话缓存，必须验证重新加载后的持久化结果。
            assertEquals(1, battles.findById(session.getId()).orElseThrow().getRoundNo());
        }
    }

    @Test void stationedGatheringAndReturnDoNotSkipAheadUsingRealTime() throws Exception {
        AtomicLong time = new AtomicLong(System.currentTimeMillis() - 2 * 86_400_000L);
        try (var ignored = GameClock.use(time::get)) {
            Player player = createTestPlayer("frozen-gather", 0); Long id = player.getId(); player.setLastTick(time.get());
            WildTile tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of(), 1000);
            tile.setOccupied(true); tile.setOccupiedBy(id); wildTileRepository.save(tile);
            March station = createMarch(id, "wild", tile.getId().toString(), "粮田", 10, 10, 15, 15,
                    Map.of("truck", 1), "station", time.get() - 60_000, time.get() - 30_000, false, false);
            station.setRouteMode("land"); station.setRouteData("[[10,10],[15,15]]");
            marchService.processMarches(id, GameClock.now());
            marchService.startWildGather(id, tile.getId(), "manual");
            long finish = tile.getGatherEndAt();
            tickService.tick(id);
            assertEquals(0, tile.getMined()); assertTrue(tile.getGathering());
            assertTrue(marchRepository.findByPlayerId(id).isEmpty());
            time.set(finish); tickService.tick(id);
            assertTrue(marchRepository.findByPlayerId(id).isEmpty(), "采满后驻军仍等待手动收获和回城");
            marchService.harvestWild(id, tile.getId());
            marchService.recallWild(id, tile.getId());
            March returning = marchRepository.findByPlayerId(id).get(0);
            assertTrue(returning.getReturning());
            assertTrue(returning.getArriveAt() > GameClock.now());
            tickService.tick(id);
            assertTrue(marchRepository.existsById(returning.getId()));
            time.set(returning.getArriveAt()); tickService.tick(id);
            assertFalse(marchRepository.existsById(returning.getId()));
            assertEquals(1, armyUnitRepository.findByPlayerIdAndType(id, "truck").get(0).getCount());
        }
    }

    @Test void playerWarAndDefenderRoundPreserveRemainingGameTime() throws Exception {
        AtomicLong time = new AtomicLong(System.currentTimeMillis() - 2 * 86_400_000L);
        try (var ignored = GameClock.use(time::get)) {
            Player attacker = createTestPlayer("frozen-player-attacker", 0);
            Player defender = createTestPlayer("frozen-player-defender", 0);
            attacker.setLastTick(time.get()); defender.setLastTick(time.get());
            createArmyUnit(defender.getId(), "infantry", 20);
            PlayerCity city = new PlayerCity();
            city.setWorldId(createTestWorld().getId()); city.setOwnerId(defender.getId());
            city.setName("暂停防守城"); city.setX(20); city.setY(20); city.setLevel(5);
            city = playerCityRepository.save(city);
            long warAt = time.get() + 60_000;
            attacker.setWarAgainstId(defender.getId()); attacker.setWarAt(warAt); attacker.setWarEndAt(warAt + 120_000);
            defender.setWarAt(warAt); defender.setWarEndAt(warAt + 120_000);
            assertEquals("countdown", worldService.getWarStatus(attacker.getId(), city.getId()).get("phase"));
            time.set(warAt);
            assertEquals("combat", worldService.getWarStatus(attacker.getId(), city.getId()).get("phase"));
            March march = createMarch(attacker.getId(), "player", city.getId().toString(), city.getName(),
                    10, 10, 20, 20, Map.of("infantry", 10), "plunder", time.get() - 60_000, time.get(), false, false);
            marchService.processMarches(attacker.getId(), time.get());
            var view = marchService.getTacticalBattle(defender.getId(), march.getId());
            assertEquals("defender", view.get("side"));
            BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
            marchService.processTimedOutTacticalBattle(session.getId(), time.get());
            assertEquals(0, battles.findById(session.getId()).orElseThrow().getRoundNo());
            time.addAndGet(15_000);
            marchService.processTimedOutTacticalBattle(session.getId(), time.get());
            assertEquals(1, battles.findById(session.getId()).orElseThrow().getRoundNo());
            time.set(warAt + 120_000);
            assertEquals("ended", worldService.getWarStatus(attacker.getId(), city.getId()).get("phase"));
        }
    }
}
