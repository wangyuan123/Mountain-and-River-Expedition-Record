package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实出征类型贯穿自动野地结算、刷新、手动指挥与离线超时推进。 */
class TacticalBattleLimitIntegrationTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository battles;
    @Autowired private EntityManager entityManager;

    private March start(Long playerId, String action, String mode) {
        var world = createTestWorld();
        var tile = createWildTile(world.getId(), "ironworks", 15, 15, 1, Map.of("truck", 1), 100000);
        createArmyUnit(playerId, "truck", 1);
        var march = marchService.createDispatch(playerId, new DispatchRequest("wild", tile.getId(), action,
                Map.of("truck", 1), null, null, null, mode));
        marchService.processMarches(playerId, march.getArriveAt());
        return march;
    }

    @ParameterizedTest
    @CsvSource({"plunder,25", "conquer,35", "feint,10", "harass,15", "guerrilla,5"})
    void automaticWildBattleUsesActualDispatchAction(String action, int limit) {
        Long playerId = createTestPlayer("auto-limit-" + action, 30).getId();
        March march = start(playerId, action, "auto");
        assertTrue(marchRepository.findById(march.getId()).orElseThrow().getReturning());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        assertEquals(action, report.get("action"));
        String log = (String) report.get("report");
        assertEquals(limit, log.lines().filter(line -> line.startsWith("-- 第")).count());
        assertTrue(log.contains("回合耗尽,被迫撤退"));
    }

    @ParameterizedTest
    @CsvSource({"feint,auto", "harass,auto", "guerrilla,auto", "feint,manual", "harass,manual", "guerrilla,manual"})
    void harassmentWildVictoryLeavesResourcesAndOwnershipUntouched(String action, String mode) {
        Long playerId = createTestPlayer("harass-win-" + action + mode, 30).getId();
        var tile = createWildTile(createTestWorld().getId(), "ironworks", 15, 15, 1, Map.of(), 100000);
        tile.setMined(123);
        wildTileRepository.save(tile);
        createArmyUnit(playerId, "infantry", 10);
        createArmyUnit(playerId, "truck", 1);
        March march = marchService.createDispatch(playerId, new DispatchRequest("wild", tile.getId(), action,
                Map.of("infantry", 10, "truck", 1), null, Map.of("food", 50), null, mode, "occupy"));
        assertEquals("withdraw", march.getConquerMode());
        marchService.processMarches(playerId, march.getArriveAt());
        if ("manual".equals(mode)) {
            marchService.executeTacticalRound(playerId, march.getId(), new GameDtos.BattleCommandRequest(Map.of(), 0));
        }
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        assertEquals(true, report.get("attackerWin"));
        assertEquals(false, report.get("wildConquered"));
        assertTrue(((Map<?, ?>) report.get("plunder")).values().stream().allMatch(amount -> ((Number) amount).intValue() == 0));
        var after = wildTileRepository.findById(tile.getId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(after.getOccupied()));
        assertNull(after.getOccupiedBy());
        assertEquals(123, after.getMined());
        var returning = marchRepository.findById(march.getId()).orElseThrow();
        assertTrue(returning.getReturning());
        var cargo = JsonUtil.parseIntMap(returning.getCarryRes());
        assertEquals(50, cargo.get("food"));
        assertEquals(50, cargo.values().stream().mapToInt(Integer::intValue).sum());
    }

    @ParameterizedTest
    @CsvSource({"feint,auto", "harass,auto", "guerrilla,auto", "feint,manual", "harass,manual", "guerrilla,manual"})
    void highestLevelNpcHarassmentCannotAddDiamondOrResourceRewards(String action, String mode) {
        Long playerId = createTestPlayer("npc-short-" + action + mode, 30).getId();
        var npc = new com.wargame.model.entity.Bandit();
        npc.setWorldId(createTestWorld().getId());
        npc.setName("最高级据点");
        npc.setLevel(30);
        npc.setX(15);
        npc.setY(15);
        npc.setArmy("{}");
        npc.setDefeated(false);
        banditRepository.save(npc);
        createArmyUnit(playerId, "infantry", 10);
        March march = marchService.createDispatch(playerId, new DispatchRequest("bandit", npc.getId(), action,
                Map.of("infantry", 10), null, null, null, mode));
        marchService.processMarches(playerId, march.getArriveAt());
        if ("manual".equals(mode)) {
            marchService.executeTacticalRound(playerId, march.getId(), new GameDtos.BattleCommandRequest(Map.of(), 0));
        }
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        assertEquals(true, report.get("attackerWin"));
        assertTrue(((Map<?, ?>) report.get("plunder")).values().stream().allMatch(amount -> ((Number) amount).intValue() == 0));
        assertTrue(JsonUtil.parseIntMap(marchRepository.findById(march.getId()).orElseThrow().getCarryRes())
                .values().stream().allMatch(amount -> amount == 0));
        assertTrue(report.get("subject").toString().startsWith(com.wargame.service.BattleService.actionName(action)));
    }

    @ParameterizedTest
    @CsvSource({"plunder,25,false", "conquer,35,false", "plunder,25,true", "conquer,35,true", "feint,10,false", "harass,15,false", "guerrilla,5,false", "feint,10,true", "harass,15,true", "guerrilla,5,true"})
    void restoredManualBattleAndTimeoutsStopAtSameDisplayedLimit(String action, int limit, boolean timeout) {
        Long playerId = createTestPlayer("manual-limit-" + action + timeout, 30).getId();
        March march = start(playerId, action, "manual");
        Long marchId = march.getId();
        for (int round = 1; round < limit; round++) {
            if (timeout) {
                BattleSession session = battles.findByMarchId(marchId).orElseThrow();
                marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
            } else {
                var view = marchService.executeTacticalRound(playerId, marchId,
                        new GameDtos.BattleCommandRequest(Map.of(), round - 1));
                assertEquals(false, view.get("finished"));
            }
        }
        battles.flush();
        entityManager.clear();
        var restored = marchService.getTacticalBattle(playerId, marchId);
        assertEquals(limit, restored.get("maxRound"));
        assertEquals(limit - 1, restored.get("round"));
        assertEquals(action, restored.get("action"));
        assertFalse(marchRepository.findById(marchId).orElseThrow().getReturning());
        if (timeout) {
            BattleSession session = battles.findByMarchId(marchId).orElseThrow();
            marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
        } else {
            var finalView = marchService.executeTacticalRound(playerId, marchId,
                    new GameDtos.BattleCommandRequest(Map.of(), limit - 1));
            assertEquals(true, finalView.get("finished"));
            assertEquals(limit, finalView.get("round"));
            assertEquals(limit, finalView.get("maxRound"));
        }
        assertTrue(battles.findByMarchId(marchId).isEmpty());
        assertTrue(marchRepository.findById(marchId).orElseThrow().getReturning());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(playerId).get(0).getData());
        assertFalse((Boolean) report.get("attackerWin"));
        assertEquals(limit, ((String) report.get("report")).lines().filter(line -> line.startsWith("-- 第")).count());
    }
}
