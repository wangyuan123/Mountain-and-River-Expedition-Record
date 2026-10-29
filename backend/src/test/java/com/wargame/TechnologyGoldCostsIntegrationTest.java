package com.wargame;

import com.wargame.model.entity.Resources;
import com.wargame.model.entity.TechResearchQueue;
import com.wargame.repository.TechResearchQueueRepository;
import com.wargame.service.CityScope;
import com.wargame.service.PrerequisiteService;
import com.wargame.service.TechService;
import com.wargame.util.GameClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离H2数据库验证大额科研扣款、队列快照及旧价格兼容，不连接运行中的游戏数据库。 */
class TechnologyGoldCostsIntegrationTest extends BaseServiceTest {
    @Autowired TechService research;
    @Autowired TechResearchQueueRepository queues;
    @Autowired PrerequisiteService prerequisites;
    @Autowired CityScope cityScope;
    @Autowired EntityManager entityManager;

    @ParameterizedTest
    @CsvSource({"prod_food,20000000", "attack_tech,30000000", "arm_engine,50000000",
            "air_engine,65000000", "nav_engine,80000000"})
    void finalLevelSnapshotAndCrossCityRefundSurviveDatabaseReload(String tech, int gold) {
        long playerId = createTestPlayer().getId();
        createTechnology(playerId, tech, 9);
        for (var required : prerequisites.rules().path("technologies").path(tech).path("levels").get(9).path("requires")) {
            var building = createBuilding(playerId, required.path("building").asText(), required.path("level").asInt());
            building.setCitySlot(2);
            buildingRepository.save(building);
        }
        // 钱包高于INT及停税阈值，验证现有BIGINT库存和INT队列费用都能精确保存。
        long initialGold = 3_000_000_123L;
        var wallet = new Resources();
        wallet.setPlayerId(playerId); wallet.setCitySlot(2);
        wallet.setFood(100_000_000); wallet.setSteel(100_000_000);
        wallet.setOil(100_000_000); wallet.setRare(100_000_000); wallet.setGold(initialGold);
        resourcesRepository.saveAndFlush(wallet);

        long queueId;
        try (var ignored = cityScope.enter(playerId, 2)) {
            var result = research.startResearch(playerId, tech);
            assertEquals(true, result.get("success"), result.toString());
            queueId = ((Number) result.get("queueId")).longValue();
        }
        entityManager.flush();
        entityManager.clear();
        var stored = queues.findById(queueId).orElseThrow();
        assertEquals(gold, stored.getCostGold());
        assertEquals(2, stored.getCitySlot());
        assertEquals(10, stored.getTargetLevel());
        assertEquals(initialGold - gold, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 2).orElseThrow().getGold());
        assertEquals(500L, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().getGold());

        // 玩家切回主城后取消，仍必须按持久化快照退款给开工城。
        try (var ignored = cityScope.enter(playerId, 0)) {
            assertEquals(true, research.cancelResearch(playerId, queueId).get("success"));
        }
        entityManager.flush();
        entityManager.clear();
        assertFalse(queues.existsById(queueId));
        assertEquals(initialGold - gold + gold * 4L / 5,
                resourcesRepository.findByPlayerIdAndCitySlot(playerId, 2).orElseThrow().getGold());
        assertEquals(500L, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().getGold());
        assertEquals(9, research.getTechLevel(playerId, tech));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void persistedLegacyQueueRefundUsesActualChargeAndRoundsDown(int storedGold) {
        long playerId = createTestPlayer().getId();
        var queue = legacyQueue(playerId, storedGold);
        long queueId = queues.saveAndFlush(queue).getId();
        entityManager.clear();

        assertEquals(true, research.cancelResearch(playerId, queueId).get("success"));
        entityManager.flush();
        entityManager.clear();
        assertEquals(500L + storedGold * 4L / 5,
                resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().getGold());
        assertFalse(queues.existsById(queueId));
    }

    @Test
    void completedLegacyResearchNeverAddsTheNewGoldCharge() {
        long playerId = createTestPlayer().getId();
        var queue = legacyQueue(playerId, 0);
        queue.setFinishesAt(GameClock.now() - 1);
        queues.saveAndFlush(queue);
        entityManager.clear();

        assertEquals(1, research.settleCompletedResearch(playerId, GameClock.now()).size());
        entityManager.flush();
        entityManager.clear();
        assertEquals(10, research.getTechLevel(playerId, "military_theory"));
        assertEquals(500L, resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow().getGold());
        assertTrue(research.settleCompletedResearch(playerId, GameClock.now()).isEmpty());
    }

    private TechResearchQueue legacyQueue(long playerId, int storedGold) {
        var queue = new TechResearchQueue();
        queue.setPlayerId(playerId); queue.setCitySlot(0);
        queue.setTechType("military_theory"); queue.setTargetLevel(10);
        queue.setStartedAt(GameClock.now() - 1000); queue.setFinishesAt(GameClock.now() + 60000);
        queue.setDurationSeconds(61); queue.setCostGold(storedGold);
        return queue;
    }
}
