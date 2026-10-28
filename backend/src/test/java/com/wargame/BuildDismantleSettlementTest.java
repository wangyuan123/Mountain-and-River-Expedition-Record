package com.wargame;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.Construction;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildDismantleSettlementTest extends BaseServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlayerItemRepository items;

    @BeforeEach
    void enforceProductionSlotConstraint() {
        // Hibernate 建表不包含 V5/V32 的 MySQL 生成列；在 H2 复现生产库的零号位唯一约束。
        jdbc.execute("ALTER TABLE buildings ADD COLUMN IF NOT EXISTS is_slot0 INT "
                + "GENERATED ALWAYS AS (CASE WHEN slot = 0 THEN 1 ELSE NULL END)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_building_player_type_slot0 "
                + "ON buildings(player_id, city_slot, type, is_slot0)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"automatic", "free", "item"})
    void removingFirstHouseReleasesSlotWithoutBlockingSettlement(String completion) {
        Long playerId = createTestPlayer("dismantle-" + completion, 30).getId();
        Building removed = building(playerId, 0, 0, 1);
        Building next = building(playerId, 0, 1, 10);
        Building last = building(playerId, 0, 2, 4);
        Building foreignCity = building(playerId, 1, 0, 3);
        assertEquals(true, buildService.dismantle(playerId, "house", 0).get("success"));
        Construction job = constructionRepository.findByPlayerId(playerId).get(0);
        long refundBefore = getResources(playerId).getSteel();

        if ("free".equals(completion)) {
            assertEquals(true, buildService.freeSpeedUp(playerId, job.getId()).get("success"));
        } else if ("item".equals(completion)) {
            PlayerItem item = new PlayerItem();
            item.setPlayerId(playerId);
            item.setItemKey("speedUp10m");
            item.setCount(1);
            item.setUpdatedAt(System.currentTimeMillis());
            items.saveAndFlush(item);
            assertEquals(true, buildService.useSpeedUp(playerId, "speedUp10m", job.getId()).get("success"));
        } else {
            assertEquals(List.of("house"), buildService.completeUpgrade(playerId, job.getFinishAt()));
        }
        buildingRepository.flush();
        assertFalse(buildingRepository.existsById(removed.getId()));
        assertFalse(constructionRepository.existsById(job.getId()));
        assertEquals(0, buildingRepository.findById(next.getId()).orElseThrow().getSlot());
        assertEquals(10, buildingRepository.findById(next.getId()).orElseThrow().getLevel());
        assertEquals(1, buildingRepository.findById(last.getId()).orElseThrow().getSlot());
        assertEquals(0, buildingRepository.findById(foreignCity.getId()).orElseThrow().getSlot());
        assertEquals(2, buildingRepository.findByPlayerIdAndCitySlot(playerId, 0).size());
        long refund = (long) Math.floor(buildService.calcBuildCost("house", 0).get("steel") * 0.3);
        assertEquals(refundBefore + refund, getResources(playerId).getSteel());
        assertEquals(List.of(), buildService.completeUpgrade(playerId, Long.MAX_VALUE));
        assertEquals(refundBefore + refund, getResources(playerId).getSteel(), "拆除退款只结算一次");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compactionPreservesOtherConstructionTargets(boolean sameBatch) {
        Long playerId = createTestPlayer("dismantle-queues", 30).getId();
        building(playerId, 0, 0, 1);
        Building next = building(playerId, 0, 1, 3);
        Building last = building(playerId, 0, 2, 4);
        assertEquals(true, buildService.dismantle(playerId, "house", 0).get("success"));
        Construction removal = constructionRepository.findByPlayerId(playerId).get(0);
        long finish = removal.getFinishAt();
        Construction upgrade = createConstruction(playerId, "house", 5, finish - 1000,
                sameBatch ? finish : finish + 10000, 2);
        Construction pendingNew = createConstruction(playerId, "house", 1, finish - 1000, finish + 20000, 3);
        Construction foreignQueue = createConstruction(playerId, "house", 4, finish, finish + 30000, 1);
        foreignQueue.setCitySlot(1);
        constructionRepository.saveAndFlush(foreignQueue);

        buildService.completeUpgrade(playerId, finish);
        assertEquals(2, constructionRepository.findById(pendingNew.getId()).orElseThrow().getSlot());
        assertEquals(1, constructionRepository.findById(foreignQueue.getId()).orElseThrow().getSlot());
        if (!sameBatch) {
            assertEquals(1, constructionRepository.findById(upgrade.getId()).orElseThrow().getSlot());
            buildService.completeUpgrade(playerId, finish + 10000);
        }
        assertEquals(5, buildingRepository.findById(last.getId()).orElseThrow().getLevel());
        assertEquals(3, buildingRepository.findById(next.getId()).orElseThrow().getLevel());
        buildService.completeUpgrade(playerId, finish + 20000);
        var houses = buildingRepository.findByPlayerIdAndCitySlotAndTypeOrderByIdAsc(playerId, 0, "house");
        assertEquals(List.of(0, 1, 2), houses.stream().map(Building::getSlot).toList());
        assertEquals(List.of(3, 5, 1), houses.stream().map(Building::getLevel).toList());
        assertTrue(constructionRepository.findByPlayerIdAndCitySlot(playerId, 0).isEmpty());
        assertTrue(constructionRepository.existsById(foreignQueue.getId()));
    }

    private Building building(Long playerId, int city, int slot, int level) {
        Building building = new Building();
        building.setPlayerId(playerId);
        building.setCitySlot(city);
        building.setType("house");
        building.setSlot(slot);
        building.setLevel(level);
        return buildingRepository.saveAndFlush(building);
    }
}
