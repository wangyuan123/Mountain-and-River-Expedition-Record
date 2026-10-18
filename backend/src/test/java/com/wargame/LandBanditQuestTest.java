package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.Bandit;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.PlayerQuestRepository;
import com.wargame.service.WorldTerrainService;
import com.wargame.service.quest.QuestCatalog;
import com.wargame.service.quest.QuestService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LandBanditQuestTest extends BaseServiceTest {
    @Autowired QuestService quests;
    @Autowired PlayerQuestRepository progress;
    @Autowired BattleSessionRepository battles;

    @ParameterizedTest
    @CsvSource({"auto,conquer,false", "auto,plunder,false", "manual,conquer,false", "manual,plunder,false",
            "auto,conquer,true", "auto,plunder,true", "manual,conquer,true", "manual,plunder,true"})
    void onlyLandVictoriesAdvanceTheQuest(String mode, String action, boolean sea) {
        var world = createTestWorld();
        char[] mask = "0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE).toCharArray();
        if (sea) mask[20 * WorldTerrainService.SIZE + 20] = '1';
        world.setTerrainData(new String(mask));
        world.setTerrainVersion(WorldTerrainService.TERRAIN_VERSION);
        worldMapRepository.saveAndFlush(world);
        var player = createTestPlayer();
        resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).ifPresent(r -> { r.setOil(100_000); resourcesRepository.save(r); });
        quests.getPlayerQuests(player.getId());
        var target = new Bandit();
        target.setWorldId(world.getId()); target.setX(20); target.setY(20); target.setLevel(1);
        target.setName(sea ? "日寇海域守军 Lv.1" : "寇 Lv.1");
        target.setArmy("{\"fighter\":1}"); target.setDefeated(false);
        banditRepository.saveAndFlush(target);
        createArmyUnit(player.getId(), "fighter", 100);
        var march = marchService.createDispatch(player.getId(), new DispatchRequest("bandit", target.getId(),
                action, Map.of("fighter", 100), null, null, null, mode));
        long arrival = march.getArriveAt();
        marchService.processMarches(player.getId(), arrival);
        if ("manual".equals(mode)) {
            Long battleId = battles.findByMarchId(march.getId()).orElseThrow().getId();
            for (int round = 1; round <= 30 && battles.existsById(battleId); round++)
                marchService.processTimedOutTacticalBattle(battleId, arrival + round * 15_000L);
        }
        assertTrue(march.getBattleWon());
        assertTrue(target.getDefeated());
        var quest = progress.findByPlayerIdAndQuestId(player.getId(), "q3_5").orElseThrow();
        assertEquals(sea ? 0 : 1, quest.getProgress());
        assertEquals(sea ? "in_progress" : "completed", quest.getStatus());
        marchService.processMarches(player.getId(), march.getArriveAt());
        assertEquals(sea ? 0 : 1, quest.getProgress(), "返程不重复推进任务");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "claimed"})
    void changedObjectivePreservesExistingCompletionAndClaims(String status) {
        var player = createTestPlayer();
        quests.getPlayerQuests(player.getId());
        var row = progress.findByPlayerIdAndQuestId(player.getId(), "q3_5").orElseThrow();
        row.setProgress(1); row.setStatus(status); row.setCompletedAt(123L);
        row.setClaimedAt("claimed".equals(status) ? 456L : null);
        progress.saveAndFlush(row);
        Long id = row.getId();
        quests.getPlayerQuests(player.getId());
        quests.onEvent(player.getId(), "BANDIT_DEFEAT", null, 1);
        assertEquals(id, row.getId());
        assertEquals(status, row.getStatus());
        assertEquals(1, row.getProgress());
        assertEquals(123L, row.getCompletedAt());
        assertEquals("claimed".equals(status) ? 456L : null, row.getClaimedAt());
        var definition = QuestCatalog.findQuest("q3_5");
        assertEquals("击败陆地寇", definition.title());
        assertEquals("q3_4", definition.requires());
        assertEquals(1, definition.targetValue());
        assertEquals(new QuestCatalog.Reward(10000, 8000, 4000, 1000, 1000, 0, null, null, null, 0), definition.reward());
    }
}
