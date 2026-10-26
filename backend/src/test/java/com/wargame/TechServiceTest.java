package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.EquipmentService;
import com.wargame.service.OfficerEquipmentProgressService;
import com.wargame.service.SpeedUpSupport;
import com.wargame.service.TechService;
import com.wargame.service.PrerequisiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TechServiceTest {

    @Test
    void everyTechnologyUsesTheDesignedLabUnlockAndTargetLevelLimit() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/game/tech-building-prerequisites.json")) {
            var rules = mapper.readTree(stream).path("technologies");
            Map<Integer, Integer> counts = new TreeMap<>();
            for (var tech : GameData.TECHS.values()) {
                var definition = rules.path(tech.key());
                assertEquals(tech.labReq(), definition.path("labFloor").asInt(), tech.key());
                counts.merge(tech.labReq(), 1, Integer::sum);
                for (int target = 1; target <= 10; target++) {
                    int required = 0;
                    for (var requirement : definition.path("levels").get(target - 1).path("requires")) {
                        if ("lab".equals(requirement.path("building").asText())) required = requirement.path("level").asInt();
                    }
                    assertEquals(Math.max(tech.labReq(), target), required, tech.key() + " target " + target);
                }
            }
            assertEquals(Map.of(1, 6, 2, 10, 3, 6, 4, 1, 5, 1), counts);
        }
    }

    private TechnologyRepository technologyRepository;
    private ResourcesRepository resourcesRepository;
    private BuildingRepository buildingRepository;
    private PlayerRepository playerRepository;
    private TechResearchQueueRepository techResearchQueueRepository;
    private SpeedUpSupport speedUpSupport;
    private CityScope cityScope;
    private OfficerRepository officerRepository;
    private EquipmentService equipmentService;
    private OfficerEquipmentProgressService equipmentProgress;
    private TechService techService;

    @BeforeEach
    void setUp() throws Exception {
        technologyRepository = mock(TechnologyRepository.class);
        resourcesRepository = mock(ResourcesRepository.class);
        buildingRepository = mock(BuildingRepository.class);
        playerRepository = mock(PlayerRepository.class);
        techResearchQueueRepository = mock(TechResearchQueueRepository.class);
        speedUpSupport = mock(SpeedUpSupport.class);
        cityScope = mock(CityScope.class);
        officerRepository = mock(OfficerRepository.class);
        equipmentService = mock(EquipmentService.class);
        equipmentProgress = mock(OfficerEquipmentProgressService.class);

        when(cityScope.slot(anyLong())).thenReturn(0);
        when(playerRepository.lockById(anyLong())).thenReturn(Optional.of(new Player()));

        techService = new TechService(technologyRepository, resourcesRepository, buildingRepository,
                playerRepository, techResearchQueueRepository, speedUpSupport);

        // Inject cityScope
        Field csField = TechService.class.getDeclaredField("cityScope");
        csField.setAccessible(true);
        csField.set(techService, cityScope);
        ReflectionTestUtils.setField(techService, "officerRepository", officerRepository);
        ReflectionTestUtils.setField(techService, "equipmentService", equipmentService);
        ReflectionTestUtils.setField(techService, "equipmentProgress", equipmentProgress);
        when(equipmentService.attributes(any(Officer.class))).thenAnswer(invocation -> {
            Officer officer = invocation.getArgument(0);
            return new EquipmentService.Attributes(0, 0, 0,
                    Objects.requireNonNullElse(officer.getKnowledge(), 0), List.of(), List.of());
        });
        PrerequisiteService prerequisites = mock(PrerequisiteService.class);
        when(prerequisites.unmet(anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        Field rulesField = TechService.class.getDeclaredField("prerequisiteService");
        rulesField.setAccessible(true);
        rulesField.set(techService, prerequisites);
    }

    @Test
    void testCalcTechDurationIgnoresLabLevel() {
        int d0 = techService.calcTechDuration("attack_tech", 0, 1);
        assertEquals(180, d0);

        int d0Lab5 = techService.calcTechDuration("attack_tech", 0, 5);
        assertEquals(d0, d0Lab5, "Lab level only controls research access");
        assertEquals(180, d0Lab5);
        assertEquals(85320, techService.calcTechDuration("attack_tech", 5, 0));
        assertEquals(85320, techService.calcTechDuration("attack_tech", 5, 1));
        assertEquals(85320, techService.calcTechDuration("attack_tech", 5, 5));
        assertEquals(85320, techService.calcTechDuration("attack_tech", 5, 10));
        assertEquals(85320, techService.calcTechDuration("attack_tech", 5, 20));

        int d1 = techService.calcTechDuration("attack_tech", 1, 1);
        assertTrue(d1 > d0, "Higher tech level should increase duration");
    }

    @Test
    void allFiftyBaseDurationValuesAndExpandedTechnologiesFollowTheDesign() {
        Map<String, int[]> curves = Map.of(
                "attack_tech", new int[]{180,600,1800,10800,43200,85320,168420,332460,656400,1296000},
                "prod_food", new int[]{120,480,1500,5400,21600,46860,101640,220380,478020,1036800},
                "arm_engine", new int[]{300,900,2700,10800,43200,90360,188940,395100,826260,1728000},
                "nav_engine", new int[]{300,900,2700,10800,43200,96660,216180,483480,1081500,2419200},
                "air_engine", new int[]{300,900,2700,10800,43200,96660,216180,483480,1081500,2419200});
        curves.forEach((key, values) -> {
            for (int index = 0; index < values.length; index++) assertEquals(values[index], techService.calcTechDuration(key, index, 0), key + " level " + (index + 1));
        });
        for (String key : com.wargame.model.constants.TechnologyRules.EXPANDED) {
            assertEquals(10, GameData.TECHS.get(key).maxLevel());
            for (int index = 0; index < 10; index++) assertEquals(curves.get("attack_tech")[index], techService.calcTechDuration(key, index, 0));
        }
    }

    @Test
    void researchKnowledgeIsContinuousAndMultipliesWithSkillInTheResearchCity() {
        Long playerId = 100L;
        Officer mayor = new Officer();
        mayor.setRole("mayor");
        when(cityScope.slot(playerId)).thenReturn(2);
        when(officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, 2, "mayor")).thenReturn(List.of(mayor));
        int[][] cases = {{0, 85320}, {50, 81257}, {100, 77564}, {200, 71100}};
        for (int[] entry : cases) {
            mayor.setKnowledge(entry[0]);
            assertEquals(entry[1], techService.calcTechDuration(playerId, "attack_tech", 5, 10));
        }
        mayor.setKnowledge(100);
        mayor.setSkills("[{\"id\":\"research\",\"lv\":5}]");
        assertEquals(64636, techService.calcTechDuration(playerId, "attack_tech", 5, 10));
        assertEquals(91, techService.calcTechDuration(playerId, "prod_food", 0, 10));
        assertEquals(981818, techService.calcTechDuration(playerId, "attack_tech", 9, 10));
        assertEquals(1832727, techService.calcTechDuration(playerId, "nav_engine", 9, 10));

        // 空缺城市不能使用另一城的市长；装备后的有效学识由统一属性服务提供。
        when(cityScope.slot(playerId)).thenReturn(0);
        assertEquals(85320, techService.calcTechDuration(playerId, "attack_tech", 5, 10));
        when(cityScope.slot(playerId)).thenReturn(2);
        when(equipmentService.attributes(mayor)).thenReturn(new EquipmentService.Attributes(0, 0, 0, 200, List.of(), List.of()));
        assertEquals(59250, techService.calcTechDuration(playerId, "attack_tech", 5, 10));
        mayor.setKnowledge(null);
        mayor.setSkills(null);
        when(equipmentService.attributes(mayor)).thenReturn(new EquipmentService.Attributes(0, 0, 0, 0, List.of(), List.of()));
        assertEquals(85320, techService.calcTechDuration(playerId, "attack_tech", 5, 10));
    }

    @Test
    void fireDensitySharesHealthCostsAndTimeAndReportsFivePercentPerLevel() {
        Long playerId = 100L;
        for (int level : new int[]{0, 1, 5, 9}) {
            assertEquals(techService.calcTechCost("cmd_hp", level), techService.calcTechCost("fire_density", level));
            assertEquals(techService.calcTechDuration("cmd_hp", level, 10), techService.calcTechDuration("fire_density", level, 10));
        }
        assertEquals(Map.of("steel", 300, "food", 160, "rare", 30), techService.calcTechCost("fire_density", 0));
        assertEquals(0, techService.getTechLevel(playerId, "fire_density"));
        Technology tech = new Technology();
        tech.setLevel(5);
        when(technologyRepository.findByPlayerIdAndType(playerId, "fire_density")).thenReturn(List.of(tech));
        assertEquals(25, techService.calcTechBonus(playerId, "firepower_all"));
    }

    @Test
    void testStartResearchLocksMayorBonusesAndPreservesQueueAfterLabChanges() {
        Long playerId = 100L;
        Building lab = new Building();
        lab.setType("lab");
        lab.setLevel(10);
        when(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "lab"))
                .thenReturn(List.of(lab));

        Technology tech = new Technology();
        tech.setLevel(5);
        when(technologyRepository.findByPlayerIdAndType(playerId, "attack_tech"))
                .thenReturn(List.of(tech));
        Officer mayor = new Officer();
        mayor.setKnowledge(100);
        mayor.setSkills("[{\"id\":\"research\",\"lv\":5}]");
        when(officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, 0, "mayor")).thenReturn(List.of(mayor));

        // No active queue
        when(techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId))
                .thenReturn(Collections.emptyList());

        // Resources enough
        Resources res = new Resources();
        res.setSteel(10000);
        res.setFood(10000);
        when(resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0)).thenReturn(Optional.of(res));

        Map<String, Object> result = techService.startResearch(playerId, "attack_tech");
        assertTrue((Boolean) result.get("success"));
        assertEquals("attack_tech", result.get("techType"));
        assertEquals(6, result.get("targetLevel"));
        assertEquals(64636, result.get("durationSeconds"));

        var saved = org.mockito.ArgumentCaptor.forClass(TechResearchQueue.class);
        verify(techResearchQueueRepository).save(saved.capture());
        TechResearchQueue queue = saved.getValue();
        assertEquals(64636000L, queue.getFinishesAt() - queue.getStartedAt());
        when(techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)).thenReturn(List.of(queue));
        mayor.setKnowledge(200);
        lab.setLevel(1);
        Map<String, Object> active = techService.getActiveResearch(playerId);
        assertEquals(64636, active.get("durationSeconds"));
        assertEquals(result.get("finishesAt"), active.get("finishesAt"), "已开工队列不能重新计算加成");
    }

    @Test
    void legacyResearchKeepsItsStoredLabDiscountAndFinishesWithoutRecheckingAccess() {
        Long playerId = 100L;
        TechResearchQueue queue = new TechResearchQueue();
        queue.setId(78L);
        queue.setPlayerId(playerId);
        queue.setCitySlot(0);
        queue.setTechType("attack_tech");
        queue.setTargetLevel(6);
        queue.setStartedAt(1000L);
        queue.setDurationSeconds(58760);
        queue.setFinishesAt(58761000L);
        when(techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId)).thenReturn(List.of(queue));
        var active = techService.getActiveResearch(playerId);
        assertEquals(58760, active.get("durationSeconds"));
        assertEquals(58761000L, active.get("finishesAt"));
        when(techResearchQueueRepository.findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(playerId, 58761000L))
                .thenReturn(List.of(queue));
        assertEquals(1, techService.settleCompletedResearch(playerId, 58761000L).size());
        var saved = org.mockito.ArgumentCaptor.forClass(Technology.class);
        verify(technologyRepository).save(saved.capture());
        assertEquals(6, saved.getValue().getLevel());
        verifyNoInteractions(buildingRepository);
    }

    @Test
    void testStartResearchRejectsWhenAlreadyResearching() {
        Long playerId = 100L;
        TechResearchQueue active = new TechResearchQueue();
        active.setId(99L);
        active.setPlayerId(playerId);
        active.setTechType("attack_tech");
        active.setTargetLevel(1);
        active.setFinishesAt(System.currentTimeMillis() + 60000L);

        when(techResearchQueueRepository.findByPlayerIdOrderByStartedAtAscIdAsc(playerId))
                .thenReturn(List.of(active));

        Map<String, Object> result = techService.startResearch(playerId, "defense_tech");
        assertFalse((Boolean) result.get("success"));
        assertTrue(((String) result.get("message")).contains("正在研发"));
    }

    @Test
    void testCancelResearchRefundsResources() {
        Long playerId = 100L;
        TechResearchQueue q = new TechResearchQueue();
        q.setId(55L);
        q.setPlayerId(playerId);
        q.setCitySlot(0);
        q.setTechType("attack_tech");
        q.setCostSteel(1000);
        q.setCostFood(500);

        when(techResearchQueueRepository.findById(55L)).thenReturn(Optional.of(q));
        Resources res = new Resources();
        res.setSteel(100);
        res.setFood(100);
        when(resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0)).thenReturn(Optional.of(res));

        Map<String, Object> result = techService.cancelResearch(playerId, 55L);
        assertTrue((Boolean) result.get("success"));
        assertEquals(100 + 800, res.getSteel());
        assertEquals(100 + 400, res.getFood());
        verify(techResearchQueueRepository).delete(q);
        verifyNoInteractions(equipmentProgress);
    }

    @Test
    void testSettleCompletedResearch() {
        Long playerId = 100L;
        TechResearchQueue q = new TechResearchQueue();
        q.setId(77L);
        q.setPlayerId(playerId);
        q.setTechType("attack_tech");
        q.setTargetLevel(2);
        q.setCostSteel(1000);
        q.setFinishesAt(1000L);

        when(techResearchQueueRepository.findByPlayerIdAndFinishesAtLessThanEqualOrderByFinishesAtAscIdAsc(eq(playerId), anyLong()))
                .thenReturn(List.of(q));
        when(technologyRepository.findByPlayerIdAndType(playerId, "attack_tech"))
                .thenReturn(Collections.emptyList());

        List<String> messages = techService.settleCompletedResearch(playerId, 2000L);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("攻击科技"));
        assertTrue(messages.get(0).contains("Lv.2"));
        verify(technologyRepository).save(any(Technology.class));
        verify(techResearchQueueRepository).delete(q);
        verify(equipmentProgress).event(playerId, "develop", "tech-77", 1);
    }
}
