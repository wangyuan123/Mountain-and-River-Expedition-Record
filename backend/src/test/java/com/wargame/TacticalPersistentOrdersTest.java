package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.model.entity.PlayerCity;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.BattleService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证玩家指令跨回合、刷新及离线结算持续生效，并隔离双方和不同战斗。 */
class TacticalPersistentOrdersTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository battles;
    @Autowired private EntityManager entityManager;

    @ParameterizedTest
    @ValueSource(strings = {"HOLD", "RETREAT"})
    void manualOrdersSurviveReloadEmptySubmissionAndOfflineRoundsUntilReplaced(String action) {
        Long playerId = createTestPlayer("persistent-attacker", 30).getId();
        March march = startWildBattle(playerId);
        BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();

        Map<String, Object> view = command(playerId, march, 0, "infantry", action);
        int firstPosition = position(view, "infantry");
        int infantryStep = (int) (UnitDef.UNITS.get("infantry").spd() * 50);
        assertEquals("HOLD".equals(action) ? 1500 : 1500 - infantryStep, firstPosition);
        battles.flush();
        entityManager.clear();
        view = marchService.getTacticalBattle(playerId, march.getId());
        assertEquals(action, order(view, "infantry").action().name());
        assertEquals("ADVANCE", ((Map<?, ?>) view.get("defaultActions")).get("infantry"),
                "本场指令不能改写账号预设");

        view = marchService.executeTacticalRound(playerId, march.getId(), new GameDtos.BattleCommandRequest(
                Map.of("infantry", new GameDtos.BattleUnitOrderRequest(null, null)), 1));
        assertMovement(action, firstPosition, position(view, "infantry"));
        for (int round = 3; round <= 5; round++) {
            int previous = position(view, "infantry");
            session = battles.findByMarchId(march.getId()).orElseThrow();
            marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
            view = marchService.getTacticalBattle(playerId, march.getId());
            assertEquals(round, view.get("round"));
            assertEquals(action, order(view, "infantry").action().name());
            assertMovement(action, previous, position(view, "infantry"));
        }

        int previous = position(view, "infantry");
        view = command(playerId, march, 5, "infantry", "ADVANCE");
        assertTrue(position(view, "infantry") > previous, "新指令在当前回合替换旧指令");
        previous = position(view, "infantry");
        session = battles.findByMarchId(march.getId()).orElseThrow();
        marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
        view = marchService.getTacticalBattle(playerId, march.getId());
        assertTrue(position(view, "infantry") > previous);
        assertEquals(BattleService.CommandAction.ADVANCE, order(view, "infantry").action());
        assertFalse(((Map<?, ?>) view.get("orders")).containsKey("truck"), "未手动下令的兵种继续使用账号预设");

        March another = startWildBattle(playerId);
        assertTrue(((Map<?, ?>) marchService.getTacticalBattle(playerId, another.getId()).get("orders")).isEmpty(),
                "另一场战斗不能继承本场指令");
    }

    @Test
    void attackerAndDefenderKeepIndependentOrdersWhenEitherSideSubmits() {
        Long attackerId = createTestPlayer("persistent-pvp-attacker", 30).getId();
        Long defenderId = createTestPlayer("persistent-pvp-defender", 30).getId();
        createArmyUnit(defenderId, "infantry", 100);
        PlayerCity city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defenderId);
        city.setName("持续指令防守城");
        city.setLevel(5);
        city.setX(20);
        city.setY(20);
        city.setForts("{}");
        city.setResources("{}");
        playerCityRepository.save(city);
        long now = System.currentTimeMillis();
        var attacker = playerRepository.findById(attackerId).orElseThrow();
        attacker.setWarAgainstId(defenderId);
        attacker.setWarAt(now - 1);
        attacker.setWarEndAt(now + 60_000);
        playerRepository.save(attacker);
        March march = createMarch(attackerId, "player", String.valueOf(city.getId()), city.getName(),
                10, 10, 20, 20, Map.of("infantry", 100), "plunder", now - 60_000, now - 1, false, false);
        marchService.getTacticalBattle(attackerId, march.getId());
        BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
        session.setInitialDistance(5000);
        session.setAttackerPositions(JsonUtil.toJson(Map.of("infantry", 1500)));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("infantry", 3500)));
        battles.saveAndFlush(session);

        command(defenderId, march, 0, "infantry", "HOLD");
        Map<String, Object> attackView = command(attackerId, march, 1, "infantry", "RETREAT");
        int previous = position(attackView, "infantry");
        assertEquals(BattleService.CommandAction.RETREAT, order(attackView, "infantry").action());
        Map<String, Object> defendView = marchService.getTacticalBattle(defenderId, march.getId());
        assertEquals(BattleService.CommandAction.HOLD, order(defendView, "infantry").action());
        assertEquals(3500, position(defendView, "infantry"));

        session = battles.findByMarchId(march.getId()).orElseThrow();
        marchService.processTimedOutTacticalBattle(session.getId(), session.getRoundDeadlineAt());
        assertTrue(position(marchService.getTacticalBattle(attackerId, march.getId()), "infantry") < previous);
        assertEquals(3500, position(marchService.getTacticalBattle(defenderId, march.getId()), "infantry"));
    }

    @Test
    void baseUnitOrderPersistsForEveryEquipmentGroupAndDropsEliminatedFocus() {
        Long playerId = createTestPlayer("persistent-groups", 30).getId();
        March march = startWildBattle(playerId);
        BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
        session.setAttackerArmy(JsonUtil.toJson(Map.of("infantry~p1", 50, "infantry~p2", 50)));
        session.setAttackerPositions(JsonUtil.toJson(Map.of("infantry~p1", 1500, "infantry~p2", 1500)));
        battles.saveAndFlush(session);
        Map<String, Object> view = marchService.executeTacticalRound(playerId, march.getId(),
                new GameDtos.BattleCommandRequest(Map.of("infantry", new GameDtos.BattleUnitOrderRequest("HOLD", "truck")), 0));
        assertEquals("truck", order(view, "infantry~p1").focusTarget());
        assertEquals("truck", order(view, "infantry~p2").focusTarget());
        // 模拟集火目标全灭但其他敌军存活，刷新后应清除目标并保留待命。
        session = battles.findByMarchId(march.getId()).orElseThrow();
        session.setDefenderArmy(JsonUtil.toJson(Map.of("transport", 100)));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("transport", 5000)));
        battles.saveAndFlush(session);
        view = marchService.getTacticalBattle(playerId, march.getId());
        assertNull(order(view, "infantry~p1").focusTarget());
        view = marchService.executeTacticalRound(playerId, march.getId(), new GameDtos.BattleCommandRequest(Map.of(), 1));
        for (String group : new String[]{"infantry~p1", "infantry~p2"}) {
            assertEquals(1500, position(view, group));
            assertEquals(BattleService.CommandAction.HOLD, order(view, group).action());
            assertNull(order(view, group).focusTarget());
        }
    }

    @Test
    void staleRequestCannotReplaceSavedOrders() {
        Long playerId = createTestPlayer("persistent-stale", 30).getId();
        March march = startWildBattle(playerId);
        command(playerId, march, 0, "infantry", "HOLD");
        Map<String, Object> view = command(playerId, march, 0, "infantry", "ADVANCE");
        assertEquals(1, view.get("round"));
        assertEquals(BattleService.CommandAction.HOLD, order(view, "infantry").action());
        assertEquals(1500, position(view, "infantry"));
    }

    private March startWildBattle(Long playerId) {
        var world = createTestWorld();
        var tile = createWildTile(world.getId(), "forest", 15, 15, 1, Map.of("truck", 100_000), 0);
        for (String unit : new String[]{"infantry", "truck"}) {
            var existing = armyUnitRepository.findByPlayerIdAndType(playerId, unit);
            if (existing.isEmpty()) createArmyUnit(playerId, unit, 100);
            else {
                existing.get(0).setCount(100);
                armyUnitRepository.save(existing.get(0));
            }
        }
        March march = marchService.createDispatch(playerId, new DispatchRequest("wild", tile.getId(), "conquer",
                Map.of("infantry", 100, "truck", 100), null, null, null, "manual"));
        marchService.processMarches(playerId, march.getArriveAt());
        BattleSession session = battles.findByMarchId(march.getId()).orElseThrow();
        session.setInitialDistance(5000);
        session.setAttackerPositions(JsonUtil.toJson(Map.of("infantry", 1500, "truck", 1500)));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("truck", 5000)));
        battles.saveAndFlush(session);
        return march;
    }

    private Map<String, Object> command(Long playerId, March march, int round, String unit, String action) {
        return marchService.executeTacticalRound(playerId, march.getId(), new GameDtos.BattleCommandRequest(
                Map.of(unit, new GameDtos.BattleUnitOrderRequest(action, null)), round));
    }

    private BattleService.UnitOrder order(Map<String, Object> view, String unit) {
        return (BattleService.UnitOrder) ((Map<?, ?>) view.get("orders")).get(unit);
    }

    private int position(Map<String, Object> view, String unit) {
        return ((Number) ((Map<?, ?>) view.get("attackerPositions")).get(unit)).intValue();
    }

    private void assertMovement(String action, int previous, int current) {
        if ("HOLD".equals(action)) assertEquals(previous, current);
        else assertTrue(current < previous, "后退必须在后续回合继续执行");
    }
}
