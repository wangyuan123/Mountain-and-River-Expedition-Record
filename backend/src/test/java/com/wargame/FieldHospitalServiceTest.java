package com.wargame;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.TechResearchQueue;
import com.wargame.repository.TechResearchQueueRepository;
import com.wargame.service.PrerequisiteService;
import com.wargame.service.TechService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 验证医院建设、医疗科研的本城依赖、军事卡槽和历史队列兼容。 */
class FieldHospitalServiceTest extends BaseServiceTest {
    @Autowired private PrerequisiteService prerequisites;
    @Autowired private TechService tech;
    @Autowired private TechResearchQueueRepository researchQueues;
    private Long playerId;

    @BeforeEach
    void setUp() {
        playerId = createTestPlayer("hospital-player", 30).getId();
        giveResources(playerId, 1000000, 1000000, 1000000, 1000000, 1000000);
        createBuilding(playerId, "command", 10);
        createBuilding(playerId, "house", 10);
        createBuilding(playerId, "academy", 10);
        createBuilding(playerId, "farm", 10);
        createBuilding(playerId, "lab", 9);
    }

    @Test
    void hospitalConstructionChecksAllCivilianDependenciesBeforeCharging() {
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "house"));
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "academy"));
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "farm"));
        long steel = getResources(playerId).getSteel();
        var denied = buildService.upgrade(playerId, "hospital", 0);
        assertEquals(false, denied.get("success"));
        assertEquals(3, prerequisites.unmet(playerId, 0, "buildings", "hospital", 1).size());
        assertEquals(steel, getResources(playerId).getSteel());
        assertTrue(constructionRepository.findByPlayerId(playerId).isEmpty());
    }

    @Test
    void hospitalUpgradesToTenWithoutRequiringResearchOrTransit() {
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "lab"));
        for (int level = 1; level <= 10; level++) {
            assertEquals(true, buildService.upgrade(playerId, "hospital", 0).get("success"), "医院 Lv." + level);
            buildService.completeUpgrade(playerId, System.currentTimeMillis() + 86401000L);
        }
        assertEquals(10, buildService.buildingLevel(playerId, "hospital"));
        assertEquals(1, buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "hospital").size());
        assertEquals(false, buildService.upgrade(playerId, "hospital", 1).get("success"));
    }

    @Test
    void fullMilitaryGroupRejectsHospitalBeforePayment() {
        for (int slot = 1; slot <= 32; slot++) {
            Building house = new Building();
            house.setPlayerId(playerId);
            house.setCitySlot(0);
            house.setType("house");
            house.setSlot(slot);
            house.setLevel(1);
            buildingRepository.save(house);
        }
        long steel = getResources(playerId).getSteel();
        var denied = buildService.upgrade(playerId, "hospital", 0);
        assertEquals(false, denied.get("success"));
        assertTrue(denied.get("message").toString().contains("军事建筑已达上限(" + com.wargame.model.constants.GameConstants.GROUP_SLOTS_ARMY + "栋"));
        assertEquals(steel, getResources(playerId).getSteel());
    }

    @Test
    void transitAndAnotherCityHospitalCannotEnableMedicalResearch() {
        createBuilding(playerId, "transit", 10);
        Building hospital = createBuilding(playerId, "hospital", 10);
        hospital.setCitySlot(1);
        buildingRepository.save(hospital);
        long steel = getResources(playerId).getSteel();
        var denied = tech.startResearch(playerId, "log_medical");
        assertEquals(false, denied.get("success"));
        assertTrue(denied.get("message").toString().contains("本城战地医院"));
        assertEquals(steel, getResources(playerId).getSteel());
        assertTrue(researchQueues.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).isEmpty());
        hospital.setCitySlot(0);
        buildingRepository.save(hospital);
        assertEquals(true, tech.startResearch(playerId, "log_medical").get("success"));
    }

    @Test
    void pendingHospitalConstructionDoesNotEnableResearchButCompletedHospitalDoes() {
        assertEquals(true, buildService.upgrade(playerId, "hospital", 0).get("success"));
        assertEquals(false, tech.startResearch(playerId, "log_medical").get("success"));
        buildService.completeUpgrade(playerId, System.currentTimeMillis() + 60000L);
        assertEquals(true, tech.startResearch(playerId, "log_medical").get("success"));
        TechResearchQueue queue = researchQueues.findByPlayerIdOrderByStartedAtAscIdAsc(playerId).get(0);
        assertTrue(queue.getPrerequisiteRequirements().contains("hospital"));
        assertFalse(queue.getPrerequisiteRequirements().contains("transit"));
        assertEquals(false, buildService.dismantle(playerId, "hospital", 0).get("success"));
        tech.cancelResearch(playerId, queue.getId());
        assertEquals(true, buildService.dismantle(playerId, "hospital", 0).get("success"));
        assertEquals(false, tech.startResearch(playerId, "log_medical").get("success"));
    }

    @Test
    void hospitalConstructionProtectsTheRequiredAcademyAndCivilianBuildings() {
        for (String type : new String[]{"house", "academy", "farm"}) {
            Building building = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, type).get(0);
            building.setLevel(1);
            buildingRepository.save(building);
        }
        assertEquals(true, buildService.upgrade(playerId, "hospital", 0).get("success"));
        for (String type : new String[]{"house", "academy", "farm"}) {
            var denied = buildService.dismantle(playerId, type, 0);
            assertEquals(false, denied.get("success"), type);
            assertTrue(denied.get("message").toString().contains("施工中的战地医院"));
        }
    }

    @Test
    void paidMedicalResearchKeepsItsOriginalTransitSnapshot() {
        createBuilding(playerId, "transit", 1);
        createBuilding(playerId, "hospital", 1);
        TechResearchQueue legacy = new TechResearchQueue();
        legacy.setPlayerId(playerId);
        legacy.setCitySlot(0);
        legacy.setTechType("log_medical");
        legacy.setTargetLevel(1);
        legacy.setPrerequisiteVersion("shipyard-2026-10-12-v1");
        legacy.setPrerequisiteRequirements(prerequisites.snapshot("technologies", "log_medical", 1)
                .replace("hospital", "transit"));
        legacy.setStartedAt(System.currentTimeMillis());
        legacy.setFinishesAt(System.currentTimeMillis() + 60000L);
        legacy.setDurationSeconds(60);
        researchQueues.save(legacy);
        assertNull(prerequisites.affectedTask(playerId, 0, "hospital", null, 0));
        assertEquals("研发中的医疗技术", prerequisites.affectedTask(playerId, 0, "transit", null, 0));
        tech.settleCompletedResearch(playerId, System.currentTimeMillis() + 60001L);
        assertEquals(1, tech.getTechLevel(playerId, "log_medical"));
    }
}
