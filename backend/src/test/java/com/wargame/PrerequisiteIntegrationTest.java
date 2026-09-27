package com.wargame;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.Construction;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WorldMap;
import com.wargame.service.PrerequisiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrerequisiteIntegrationTest extends BaseServiceTest {
    @Autowired private PrerequisiteService prerequisites;
    private Long playerId;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("prerequisite-player", 30);
        playerId = player.getId();
        giveResources(playerId, 100000, 100000, 100000, 100000, 100000);
    }

    @Test
    void commandSecondLevelNeedsBasicInfrastructureBeforePaying() {
        createBuilding(playerId, "command", 1);
        int steel = getResources(playerId).getSteel();
        Map<String, Object> denied = buildService.upgrade(playerId, "command", 0);
        assertEquals(false, denied.get("success"));
        assertEquals(steel, getResources(playerId).getSteel());
        assertEquals(3, ((List<?>) denied.get("missingPrerequisites")).size());

        createBuilding(playerId, "farm", 1);
        createBuilding(playerId, "refinery", 1);
        createBuilding(playerId, "house", 1);
        assertEquals(true, buildService.upgrade(playerId, "command", 0).get("success"));
    }

    @Test
    void heavyFactorySeventhLevelRequiresCommandAndStaff() {
        createBuilding(playerId, "command", 6);
        createBuilding(playerId, "heavyfactory", 6);
        createBuilding(playerId, "lightfactory", 6);
        createBuilding(playerId, "raremine", 4);
        createBuilding(playerId, "staff", 4);

        var missing = prerequisites.unmet(playerId, 0, "buildings", "heavyfactory", 7);
        assertEquals(2, missing.size());
        createBuildingLevel("command", 7);
        Map<String, Object> denied = buildService.upgrade(playerId, "heavyfactory", 0);
        assertEquals(false, denied.get("success"));
        assertEquals("staff", ((List<Map<String, Object>>) denied.get("missingPrerequisites")).get(0).get("building"));
        createBuildingLevel("staff", 5);
        assertEquals(true, buildService.upgrade(playerId, "heavyfactory", 0).get("success"));
    }

    @Test
    void twoFactoriesAndPendingUpgradeDoNotAddTheirLevels() {
        createBuilding(playerId, "lab", 4);
        createBuilding(playerId, "lightfactory", 4);
        createBuilding(playerId, "heavyfactory", 4);
        createBuilding(playerId, "factory", 1);
        Building second = new Building();
        second.setPlayerId(playerId);
        second.setCitySlot(0);
        second.setType("factory");
        second.setSlot(1);
        second.setLevel(1);
        buildingRepository.save(second);

        var missing = prerequisites.unmet(playerId, 0, "technologies", "attack_tech", 4);
        assertEquals("factory", missing.get(0).get("building"));
        assertEquals(1, missing.get(0).get("current"));

        Construction pending = new Construction();
        pending.setPlayerId(playerId);
        pending.setCitySlot(0);
        pending.setBuildingType("factory");
        pending.setSlot(0);
        pending.setTargetLevel(2);
        pending.setStartAt(System.currentTimeMillis());
        pending.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.save(pending);
        assertEquals("factory", prerequisites.unmet(playerId, 0, "technologies", "attack_tech", 4)
                .get(0).get("building"));
    }

    @Test
    void pendingSingleBuildingDemolitionStopsNewResearchBeforeCharging() {
        createBuilding(playerId, "lab", 1);
        createBuilding(playerId, "radar", 1);
        Construction demolition = new Construction();
        demolition.setPlayerId(playerId);
        demolition.setCitySlot(0);
        demolition.setBuildingType("radar");
        demolition.setSlot(null);
        demolition.setTargetLevel(0);
        demolition.setStartAt(System.currentTimeMillis());
        demolition.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.save(demolition);

        assertEquals("radar", prerequisites.unmet(playerId, 0, "technologies", "recon_level", 1)
                .get(0).get("building"));
    }

    @Test
    void dismantlingARequiredFactoryIsBlockedUntilTheJobFinishes() {
        createBuilding(playerId, "factory", 2);
        Construction pending = new Construction();
        pending.setPlayerId(playerId);
        pending.setCitySlot(0);
        pending.setBuildingType("lightfactory");
        pending.setTargetLevel(3);
        pending.setPrerequisiteVersion(prerequisites.version());
        pending.setPrerequisiteRequirements(prerequisites.snapshot("buildings", "lightfactory", 3));
        pending.setStartAt(System.currentTimeMillis());
        pending.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.save(pending);

        Map<String, Object> denied = buildService.dismantle(playerId, "factory", 0);
        assertEquals(false, denied.get("success"));
        assertTrue(((String) denied.get("message")).contains("轻装战车厂"));
    }

    @Test
    void legacyPaidConstructionHasNoRetroactivePrerequisiteBlock() {
        createBuilding(playerId, "factory", 2);
        Construction oldJob = new Construction();
        oldJob.setPlayerId(playerId);
        oldJob.setCitySlot(0);
        oldJob.setBuildingType("lightfactory");
        oldJob.setTargetLevel(3);
        oldJob.setStartAt(System.currentTimeMillis());
        oldJob.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.save(oldJob);

        assertNull(prerequisites.affectedTask(playerId, 0, "factory", 0, 1));
    }

    @Test
    void dismantlingCoastalPortCannotInvalidateResearchCityLaboratory() {
        WorldMap world = createTestWorld();
        PlayerCity coast = new PlayerCity();
        coast.setWorldId(world.getId());
        coast.setOwnerId(playerId);
        coast.setCitySlot(1);
        coast.setName("港城");
        coast.setX(20);
        coast.setY(20);
        coast.setLegacyNaval(true);
        playerCityRepository.save(coast);

        Building port = createBuilding(playerId, "port", 8);
        port.setCitySlot(1);
        buildingRepository.save(port);
        Construction lab = new Construction();
        lab.setPlayerId(playerId);
        lab.setCitySlot(0);
        lab.setBuildingType("lab");
        lab.setTargetLevel(10);
        lab.setPrerequisiteVersion(prerequisites.version());
        lab.setPrerequisiteRequirements(prerequisites.snapshot("buildings", "lab", 10));
        lab.setStartAt(System.currentTimeMillis());
        lab.setFinishAt(System.currentTimeMillis() + 60000);
        constructionRepository.save(lab);

        assertTrue(prerequisites.unmet(playerId, 0, "buildings", "lab", 10).stream()
                .noneMatch(requirement -> "port".equals(requirement.get("building"))));
        assertEquals("施工中的国防研究所", prerequisites.affectedTask(playerId, 1, "port", null, 7));

        PlayerCity alternateCoast = new PlayerCity();
        alternateCoast.setWorldId(world.getId());
        alternateCoast.setOwnerId(playerId);
        alternateCoast.setCitySlot(2);
        alternateCoast.setName("备用港城");
        alternateCoast.setX(30);
        alternateCoast.setY(30);
        alternateCoast.setLegacyNaval(true);
        playerCityRepository.save(alternateCoast);
        Building alternatePort = createBuilding(playerId, "port", 8);
        alternatePort.setCitySlot(2);
        buildingRepository.save(alternatePort);

        assertNull(prerequisites.affectedTask(playerId, 1, "port", null, 7));
    }

    private void createBuildingLevel(String type, int level) {
        Building building = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, type).get(0);
        building.setLevel(level);
        buildingRepository.save(building);
    }
}
