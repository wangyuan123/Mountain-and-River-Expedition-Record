package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class UnitPopulationCostsIntegrationTest extends BaseServiceTest {
    @Autowired ArmyService army;
    @Autowired ArmyPopulationService populationCosts;
    @Autowired ArmyPopulationBalanceRepository balances;
    @Autowired ArmyProductionQueueRepository queues;
    @Autowired WoundedUnitRepository wounded;
    @Autowired CityScope scope;
    @Autowired CityConquestService conquest;
    @Autowired PlatformTransactionManager transactions;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    private Player preparedPlayer(int civilians) {
        Player player = createTestPlayer("population-" + UUID.randomUUID(), 30);
        player.setCivilianPopulation(civilians);
        playerRepository.save(player);
        giveResources(player.getId(), 10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000);
        GameData.BUILDINGS.keySet().forEach(type -> createBuilding(player.getId(), type, 10));
        GameData.TECHS.keySet().forEach(type -> createTechnology(player.getId(), type, 10));
        PlayerCity city = new PlayerCity();
        city.setOwnerId(player.getId()); city.setCitySlot(0); city.setName("人口测试主城");
        city.setWorldId(1L); city.setX(10); city.setY(10); city.setLegacyNaval(true);
        playerCityRepository.save(city);
        return player;
    }

    private ArmyProductionQueue order(Long playerId, String type, int count, Integer population) {
        ArmyProductionQueue queue = new ArmyProductionQueue();
        queue.setPlayerId(playerId); queue.setUnitType(type); queue.setUnitCount(count);
        queue.setBuildingType(GameData.UNITS.get(type).build()); queue.setCostPopulation(population);
        queue.setStartedAt(GameClock.now()); queue.setFinishesAt(GameClock.now() + 600_000L);
        queue.setDurationSeconds(600); queue.setSpeedMultiplier(1.0);
        return queues.save(queue);
    }

    @ParameterizedTest
    @CsvSource({"infantry,1", "truck,1", "armored,2", "recon_car,2", "ltank,2", "htank,3",
            "assault,3", "rocket,5", "scout,1", "special,1", "fighter,2", "bomber,5",
            "transport,3", "destroyer,10", "sub,8", "battleship,20", "carrier,100"})
    void everyUnitChargesApprovedPopulationAndCancellationReturnsExactlyThat(String type, int perUnit) {
        Player player = preparedPlayer(1000);
        Long id = player.getId();
        var result = army.recruit(id, type, 2);
        assertEquals(true, result.get("success"), () -> type + ": " + result);
        ArmyProductionQueue queue = queues.findById((Long) result.get("queueId")).orElseThrow();
        assertEquals(perUnit * 2, queue.getCostPopulation());
        assertEquals(1000 - perUnit * 2, player.getCivilianPopulation());
        em.flush(); em.clear();
        assertEquals(perUnit * 2, queues.findById(queue.getId()).orElseThrow().getCostPopulation());
        assertTrue(army.cancelProduction(id, queue.getId()));
        assertEquals(1000, playerRepository.findById(id).orElseThrow().getCivilianPopulation());
        assertFalse(army.cancelProduction(id, queue.getId()));
    }

    @Test void carrierPopulationLimitRejectsBeforePaymentAndUsesFloorDivision() {
        Player player = preparedPlayer(299);
        var rejected = army.recruit(player.getId(), "carrier", 3);
        assertEquals(false, rejected.get("success"));
        assertEquals(2, rejected.get("maxBatch"));
        assertEquals(299, player.getCivilianPopulation());
        assertEquals(10_000_000, getResources(player.getId()).getSteel());
        assertTrue(queues.findByPlayerIdOrderByStartedAtAscIdAsc(player.getId()).isEmpty());
        var accepted = army.recruit(player.getId(), "carrier", 2);
        assertEquals(true, accepted.get("success"));
        assertEquals(99, player.getCivilianPopulation());
    }

    @ParameterizedTest
    @CsvSource({"carrier,16", "special,4", "htank,8", "rocket,6"})
    void legacyQueuesReturnOldPopulationWithoutChargingPriceDifference(String type, int paid) {
        Player player = preparedPlayer(100);
        ArmyProductionQueue queue = order(player.getId(), type, 2, null);
        assertTrue(army.cancelProduction(player.getId(), queue.getId()));
        assertEquals(100 + paid, player.getCivilianPopulation());
    }

    @Test void completingLegacyQueueKeepsOldRefundAndIsIdempotent() {
        Player player = preparedPlayer(100);
        ArmyProductionQueue queue = order(player.getId(), "carrier", 2, null);
        queue.setFinishesAt(GameClock.now() - 1);
        queues.save(queue);
        army.completeProduction(player.getId(), GameClock.now());
        army.completeProduction(player.getId(), GameClock.now());
        assertEquals(100, player.getCivilianPopulation());
        assertEquals(2, armyUnitRepository.findByPlayerIdAndType(player.getId(), "carrier").get(0).getCount());
        assertEquals(true, army.dismiss(player.getId(), "carrier", 2).get("success"));
        assertEquals(116, player.getCivilianPopulation());
        assertEquals(0, balances.findByPlayerIdAndUnitType(player.getId(), "carrier").orElseThrow().getRefundablePopulation());
    }

    @Test void mixedPopulationCostsAndRepeatedSmallDismissalsNeverCreateExtraPopulation() {
        Player player = preparedPlayer(1000);
        createArmyUnit(player.getId(), "carrier", 1);
        var result = army.recruit(player.getId(), "carrier", 2);
        ArmyProductionQueue queue = queues.findById((Long) result.get("queueId")).orElseThrow();
        queue.setFinishesAt(GameClock.now() - 1); queues.save(queue);
        army.completeProduction(player.getId(), GameClock.now());
        assertEquals(800, player.getCivilianPopulation());
        for (int i = 0; i < 3; i++) assertEquals(true, army.dismiss(player.getId(), "carrier", 1).get("success"));
        assertEquals(1008, player.getCivilianPopulation(), "新兵返还200，旧航母只返还8，分批取整也不增发人口");
        assertEquals(false, army.dismiss(player.getId(), "carrier", 1).get("success"));
        assertEquals(1008, player.getCivilianPopulation());
    }

    @Test void legacyInventoryIncludesOtherCitiesMarchesWildGarrisonsAndWoundedOnlyOnce() {
        Player player = preparedPlayer(0);
        createArmyUnit(player.getId(), "carrier", 2);
        ArmyUnit branch = createArmyUnit(player.getId(), "carrier", 6);
        branch.setCitySlot(1); armyUnitRepository.save(branch);
        createMarch(player.getId(), "wild", "1", "旧行军", 10, 10, 11, 11,
                Map.of("carrier", 3), "conquer", GameClock.now(), GameClock.now() + 600_000, false, false);
        WildTile wild = createWildTile(1L, "hill", 20, 20, 1, Map.of("carrier", 4), 1000);
        wild.setOccupied(true); wild.setOccupiedBy(player.getId()); wildTileRepository.save(wild);
        WoundedUnit batch = new WoundedUnit(); batch.setPlayerId(player.getId()); batch.setCityX(10); batch.setCityY(10);
        batch.setCityName("旧伤兵"); batch.setType("carrier"); batch.setCount(5); batch.setRecoveryPercent(50);
        batch.setCreatedAt(GameClock.now()); batch.setExpiresAt(GameClock.now() + 600_000); wounded.save(batch);
        ArmyPopulationBalance balance = populationCosts.balance(player.getId(), "carrier");
        assertEquals(20, balance.getReferenceCount());
        assertEquals(160, balance.getRefundablePopulation());
        assertEquals(true, army.dismiss(player.getId(), "carrier", 2).get("success"));
        assertEquals(16, player.getCivilianPopulation());
        assertEquals(144, populationCosts.balance(player.getId(), "carrier").getRefundablePopulation());
    }

    @Test void movementAndBattleLossesDoNotRepriceLegacyUnits() {
        Player player = preparedPlayer(0);
        ArmyUnit unit = createArmyUnit(player.getId(), "carrier", 5);
        populationCosts.balance(player.getId(), "carrier");
        unit.setCount(0); armyUnitRepository.save(unit);
        March march = createMarch(player.getId(), "wild", "1", "返程", 10, 10, 11, 11,
                Map.of("carrier", 5), "conquer", GameClock.now(), GameClock.now() + 600_000, true, false);
        marchService.returnArmy(player.getId(), Map.of("carrier", 2));
        marchRepository.delete(march);
        assertEquals(true, army.dismiss(player.getId(), "carrier", 2).get("success"));
        assertEquals(16, player.getCivilianPopulation(), "3艘战损的人口不能重新分配给2艘幸存者");
    }

    @Test void branchDismissalAndCancelReturnPopulationToTheirCityAndRespectCapacity() {
        Player player = preparedPlayer(10);
        PlayerCity city = new PlayerCity(); city.setOwnerId(player.getId()); city.setCitySlot(1); city.setName("分城");
        city.setWorldId(1L); city.setX(20); city.setY(20); city.setCivilianPopulation(1199); playerCityRepository.save(city);
        Building house = createBuilding(player.getId(), "house", 1); house.setCitySlot(1); buildingRepository.save(house);
        ArmyUnit unit = createArmyUnit(player.getId(), "carrier", 1); unit.setCitySlot(1); armyUnitRepository.save(unit);
        try (var ignored = scope.enter(player.getId(), 1)) {
            assertEquals(true, army.dismiss(player.getId(), "carrier", 1).get("success"));
            assertEquals(1200, city.getCivilianPopulation());
            ArmyProductionQueue queue = order(player.getId(), "carrier", 1, 100);
            queue.setCitySlot(1); queues.save(queue); city.setCivilianPopulation(100);
            assertTrue(army.cancelProduction(player.getId(), queue.getId()));
            assertEquals(200, city.getCivilianPopulation());
        }
        assertEquals(10, player.getCivilianPopulation());
    }

    @Test void invalidDismissalsCannotIncreaseUnitsPopulationOrRefundBudget() {
        Player player = preparedPlayer(10); createArmyUnit(player.getId(), "carrier", 1);
        for (int n : new int[] {0, -1}) assertEquals(false, army.dismiss(player.getId(), "carrier", n).get("success"));
        assertEquals(1, armyUnitRepository.findByPlayerIdAndType(player.getId(), "carrier").get(0).getCount());
        assertEquals(10, player.getCivilianPopulation());
        assertTrue(balances.findByPlayerIdAndUnitType(player.getId(), "carrier").isEmpty());
    }

    @Test void newGiftKeepsExistingDismissalBenefitWithoutRepricingOldInventory() {
        Player player = preparedPlayer(0); ArmyUnit unit = createArmyUnit(player.getId(), "carrier", 1);
        populationCosts.grant(player.getId(), "carrier", 1);
        unit.setCount(2); armyUnitRepository.save(unit);
        army.dismiss(player.getId(), "carrier", 2);
        assertEquals(108, player.getCivilianPopulation());
    }

    @Test void peacefulTransferMovesPopulationCostOnceAndLeavesUnfinishedQueueCostForCompletion() {
        Player former = preparedPlayer(0);
        Player recipient = createTestPlayer("population-recipient", 30);
        createBuilding(recipient.getId(), "house", 1);
        createArmyUnit(recipient.getId(), "carrier", 1);
        PlayerCity branch = new PlayerCity(); branch.setOwnerId(former.getId()); branch.setCitySlot(1);
        branch.setName("移交分城"); branch.setWorldId(1L); branch.setX(20); branch.setY(20); branch.setCivilianPopulation(100);
        playerCityRepository.save(branch);
        Resources resources = new Resources(); resources.setPlayerId(former.getId()); resources.setCitySlot(1); resourcesRepository.save(resources);
        Building house = createBuilding(former.getId(), "house", 1); house.setCitySlot(1); buildingRepository.save(house);
        createArmyUnit(former.getId(), "carrier", 1);
        populationCosts.grant(former.getId(), "carrier", 2);
        ArmyUnit transferred = createArmyUnit(former.getId(), "carrier", 2); transferred.setCitySlot(1); armyUnitRepository.save(transferred);
        ArmyProductionQueue queue = order(former.getId(), "carrier", 1, 100); queue.setCitySlot(1); queues.save(queue);
        int slot = conquest.peacefulTransfer(recipient.getId(), branch, GameClock.now());
        assertEquals(70, populationCosts.balance(former.getId(), "carrier").getRefundablePopulation());
        assertEquals(146, populationCosts.balance(recipient.getId(), "carrier").getRefundablePopulation());
        assertEquals(recipient.getId(), queue.getPlayerId());
        assertEquals(100, queue.getCostPopulation());
        try (var ignored = scope.enter(recipient.getId(), slot)) {
            assertEquals(true, army.dismiss(recipient.getId(), "carrier", 2).get("success"));
            assertTrue(army.cancelProduction(recipient.getId(), queue.getId()));
            assertEquals(297, branch.getCivilianPopulation());
        }
        assertEquals(49, populationCosts.balance(recipient.getId(), "carrier").getRefundablePopulation());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDismissalsAndCancellationsCannotRefundSamePopulationTwice() throws Exception {
        Long id = new TransactionTemplate(transactions).execute(status -> {
            Player player = preparedPlayer(0);
            createArmyUnit(player.getId(), "carrier", 2);
            return player.getId();
        });
        Callable<Boolean> dismiss = () -> Boolean.TRUE.equals(army.dismiss(id, "carrier", 2).get("success"));
        assertEquals(1, race(dismiss, dismiss).stream().filter(Boolean::booleanValue).count());
        assertEquals(16, playerRepository.findById(id).orElseThrow().getCivilianPopulation());
        assertEquals(0, populationCosts.balance(id, "carrier").getRefundablePopulation());
        Long queueId = new TransactionTemplate(transactions).execute(status -> order(id, "carrier", 2, 200).getId());
        Callable<Boolean> cancel = () -> army.cancelProduction(id, queueId);
        assertEquals(1, race(cancel, cancel).stream().filter(Boolean::booleanValue).count());
        assertEquals(216, playerRepository.findById(id).orElseThrow().getCivilianPopulation());
    }

    /** 使用独立提交事务并同时放行请求，验证账号锁保护实际人口与成本池。 */
    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(tasks.get(0).get(30, TimeUnit.SECONDS), tasks.get(1).get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
