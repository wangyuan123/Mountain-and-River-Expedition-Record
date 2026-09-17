package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.SpeedUpSupport;
import com.wargame.service.TechService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TechServiceTest {

    private TechnologyRepository technologyRepository;
    private ResourcesRepository resourcesRepository;
    private BuildingRepository buildingRepository;
    private PlayerRepository playerRepository;
    private TechResearchQueueRepository techResearchQueueRepository;
    private SpeedUpSupport speedUpSupport;
    private CityScope cityScope;
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

        when(cityScope.slot(anyLong())).thenReturn(0);

        techService = new TechService(technologyRepository, resourcesRepository, buildingRepository,
                playerRepository, techResearchQueueRepository, speedUpSupport);

        // Inject cityScope
        Field csField = TechService.class.getDeclaredField("cityScope");
        csField.setAccessible(true);
        csField.set(techService, cityScope);
    }

    @Test
    void testCalcTechDuration() {
        int d0 = techService.calcTechDuration("attack_tech", 0, 1);
        assertEquals(30, d0);

        int d0Lab5 = techService.calcTechDuration("attack_tech", 0, 5);
        assertTrue(d0Lab5 < d0, "Higher lab level should reduce duration");
        assertEquals(21, d0Lab5);

        int d1 = techService.calcTechDuration("attack_tech", 1, 1);
        assertTrue(d1 > d0, "Higher tech level should increase duration");
    }

    @Test
    void testStartResearchSuccess() {
        Long playerId = 100L;
        // Lab level 1
        Building lab = new Building();
        lab.setType("lab");
        lab.setLevel(1);
        when(buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, "lab"))
                .thenReturn(List.of(lab));

        // Tech level 0
        when(technologyRepository.findByPlayerIdAndType(playerId, "attack_tech"))
                .thenReturn(Collections.emptyList());

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
        assertEquals(1, result.get("targetLevel"));
        assertNotNull(result.get("durationSeconds"));

        verify(techResearchQueueRepository, times(1)).save(any(TechResearchQueue.class));
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
    }
}
