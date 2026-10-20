package com.wargame;

import com.wargame.model.constants.*;
import com.wargame.model.entity.*;
import com.wargame.repository.TechResearchQueueRepository;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyReworkIntegrationTest extends BaseServiceTest {
    @Autowired TechService research;
    @Autowired TechResearchQueueRepository queues;
    @Autowired ImportService imports;

    @Test void resourceResearchIsIndependentAndOldSharedQueueAwardsOnce() {
        var p = createTestPlayer(); long id = p.getId();
        for (String b : List.of("farm", "refinery", "oilfield", "raremine")) createBuilding(id, b, 1);
        double steel = tickService.produceOf(id, "refinery");
        double food = tickService.produceOf(id, "farm");
        createTechnology(id, "prod_food", 10);
        assertEquals(Math.floor(food * 1.5), tickService.produceOf(id, "farm"));
        assertEquals(steel, tickService.produceOf(id, "refinery"));
        var q = new TechResearchQueue(); q.setPlayerId(id); q.setCitySlot(0); q.setTechType("log_production"); q.setTargetLevel(4);
        q.setStartedAt(1L); q.setFinishesAt(2L); q.setDurationSeconds(30); q.setCostFood(400); q.setCostSteel(800); queues.saveAndFlush(q);
        int prestige = p.getPrestige();
        assertEquals(1, research.settleCompletedResearch(id, System.currentTimeMillis()).size());
        assertEquals(10, research.getTechLevel(id, "prod_food"));
        for (String key : List.of("prod_steel", "prod_oil", "prod_rare")) assertEquals(4, research.getTechLevel(id, key));
        assertEquals(prestige + 12, playerRepository.findById(id).orElseThrow().getPrestige());
        assertTrue(research.settleCompletedResearch(id, System.currentTimeMillis()).isEmpty());
    }

    @Test void loadSnapshotAppliesAcrossAllUnitBranchesAndCannotFollowResearchChanges() {
        for (UnitDef u : UnitDef.UNITS.values()) {
            int load = Objects.requireNonNullElse(u.load(), 0);
            var snapshot = TechnologyRules.actionSnapshot(Map.of("log_load", 10, UnitEquipmentEffects.key(u.key(), "load"), 20));
            assertEquals((int) Math.floor(10 * load * 1.2 * 1.5), marchService.calcArmyLoad(Map.of(u.key(), 10), snapshot), u.key());
            assertEquals(10 * load, marchService.calcArmyLoad(Map.of(u.key(), 10), Map.of()), u.key());
        }
        Map<String, Integer> live = new HashMap<>(Map.of("log_load", 2));
        var frozen = TechnologyRules.actionSnapshot(live); live.put("log_load", 10);
        assertEquals(1.1, TechnologyRules.loadMultiplier(frozen));
        assertEquals(1.0, TechnologyRules.loadMultiplier(Map.of()));
    }

    @Test void oldImportNormalizesAndFiveLevelImportExpandsOnce() {
        createTestWorld(); var p = createTestPlayer();
        imports.importFromJson(p.getId(), "{\"tech\":{\"log_production\":3,\"inf_load\":4,\"recon_radar\":2,\"unit_refit\":3}}");
        assertEquals(3, research.getTechLevel(p.getId(), "prod_food"));
        assertEquals(8, research.getTechLevel(p.getId(), "log_load"));
        assertEquals(6, research.getTechLevel(p.getId(), "recon_radar"));
        assertEquals(10, research.getTechLevel(p.getId(), "unit_refit"));
        imports.importFromJson(p.getId(), "{\"technologyRulesVersion\":\"technology-v2-20261016\",\"tech\":{\"recon_radar\":2,\"unit_refit\":3}}");
        assertEquals(4, research.getTechLevel(p.getId(), "recon_radar"));
        assertEquals(6, research.getTechLevel(p.getId(), "unit_refit"));
    }
    @Test void newImportsRemainAtExactLevelsAndLegacyPaidQueueCanSkipOddLevel() {
        createTestWorld(); var p = createTestPlayer(); Long id = p.getId();
        imports.importFromJson(id, "{\"technologyRulesVersion\":\"technology-v3-20261019\",\"tech\":{\"unit_refit\":6,\"recon_radar\":9}}");
        assertEquals(6, research.getTechLevel(id, "unit_refit"));
        assertEquals(9, research.getTechLevel(id, "recon_radar"));
        TechResearchQueue q = new TechResearchQueue(); q.setPlayerId(id); q.setCitySlot(0);
        q.setTechType("unit_refit"); q.setTargetLevel(8); q.setStartedAt(1L); q.setFinishesAt(2L);
        q.setDurationSeconds(1); q.setCostSteel(321);
        queues.saveAndFlush(q);
        assertEquals(1, research.settleCompletedResearch(id, System.currentTimeMillis()).size());
        assertEquals(8, research.getTechLevel(id, "unit_refit"));
        assertTrue(research.settleCompletedResearch(id, System.currentTimeMillis()).isEmpty());
    }

}
