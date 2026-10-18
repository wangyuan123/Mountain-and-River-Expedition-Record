package com.wargame;

import com.wargame.model.constants.BattleRules;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.service.CityScope;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实玩家分城的冻结、刷新增量、重复请求与超时结算；墙体不得造成部队结算副作用。 */
class ConquestWallSessionTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository battles;
    @Autowired private EntityManager em;
    @Autowired private CityScope scope;
    private Long attackerId;
    private Long defenderId;
    private Long targetId;
    private Long wallBuildingId;

    @BeforeEach void setup() {
        var world = createTestWorld();
        var attacker = createTestPlayer("wall-attacker", 30);
        var defender = createTestPlayer("wall-defender", 30);
        attackerId = attacker.getId(); defenderId = defender.getId();
        attacker.setMilitaryRank(4);
        city(world, attacker, 0, 10, 70);
        city(world, defender, 0, 20, 70);
        var target = city(world, defender, 1, 30, 70);
        targetId = target.getId();
        // 目标分城墙10级，与双方主城不同，验证读取正确的城市槽位。
        createBuilding(attackerId, "wall", 2);
        createBuilding(defenderId, "wall", 1);
        Building wall = new Building(); wall.setPlayerId(defenderId); wall.setCitySlot(1); wall.setType("wall"); wall.setLevel(10);
        wallBuildingId = buildingRepository.save(wall).getId();
        for (String type : new String[]{"attack_tech", "fire_density", "weapon_range", "arm_engine"}) createTechnology(attackerId, type, 10);
        long now = System.currentTimeMillis();
        attacker.setWarAgainstId(defenderId); attacker.setWarAt(now - 1); attacker.setWarEndAt(now + 3_600_000);
        em.flush();
    }

    private PlayerCity city(WorldMap world, Player owner, int slot, int x, int morale) {
        var city = new PlayerCity(); city.setWorldId(world.getId()); city.setOwnerId(owner.getId()); city.setCitySlot(slot);
        city.setName("墙体测试城" + slot); city.setX(x); city.setY(x); city.setLevel(3); city.setMorale(morale);
        playerCityRepository.save(city);
        if (slot == 0) {
            owner.setActiveCityId(city.getId()); owner.setCityPosX(x); owner.setCityPosY(x); owner.setPosX(x); owner.setPosY(x);
        } else {
            var resources = new Resources(); resources.setPlayerId(owner.getId()); resources.setCitySlot(slot);
            resources.setFood(1234); resources.setSteel(2345); resources.setOil(0); resources.setRare(0); resources.setGold(0);
            resourcesRepository.save(resources);
        }
        return city;
    }

    private March start(String action, int specialCount) {
        long now = System.currentTimeMillis();
        March m = createMarch(attackerId, "player", targetId.toString(), "目标分城", 10, 10, 30, 30,
                Map.of("special", specialCount), action, now - 60_000, now - 1, false, false);
        marchService.processMarches(attackerId, now);
        assertTrue(battles.findByMarchId(m.getId()).isPresent());
        return m;
    }

    private Map<String, Object> command(Long marchId, int round, String target) {
        return marchService.executeTacticalRound(attackerId, marchId, new GameDtos.BattleCommandRequest(
                target == null ? Map.of() : Map.of("special", new GameDtos.BattleUnitOrderRequest("ADVANCE", target)), round));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotReloadRetryAndTimeoutUseSameWallUntilSingleTerminalSettlement(boolean timeout) {
        March m = start("conquer", 5000); Long marchId = m.getId();
        var first = marchService.getTacticalBattle(attackerId, marchId);
        assertEquals(2120, first.get("initialDistance"));
        assertEquals(ConquestWallState.ATTACK_WALL, first.get("battlePhase"));
        assertEquals(10, ((ConquestWallState) first.get("wall")).level());
        assertEquals(BattleRules.VERSION, first.get("rulesVersion"));
        assertTrue(((Map<?, ?>) first.get("defenderArmy")).isEmpty());
        assertTrue(scoutReportRepository.findByPlayerId(attackerId).isEmpty());
        for (int r = 0; r < 5; r++) command(marchId, r, "wall");
        BattleSession saved = battles.findByMarchId(marchId).orElseThrow();
        BigDecimal remaining = saved.getWallHpRemaining(); Long sessionId = saved.getId();
        assertTrue(remaining.compareTo(saved.getWallMaxHp()) < 0);
        assertEquals(5, saved.getFirstCombatRound());
        // 开战后建筑降级也不能重算本场墙体；下场战斗才读取新等级。
        buildingRepository.findById(wallBuildingId).orElseThrow().setLevel(5);
        battles.flush(); em.clear();
        var restored = marchService.getTacticalBattle(attackerId, marchId);
        assertEquals(remaining, ((ConquestWallState) restored.get("wall")).hpRemaining());
        assertEquals(10, ((ConquestWallState) restored.get("wall")).level());
        var defenderView = marchService.getTacticalBattle(defenderId, marchId);
        assertEquals("defender", defenderView.get("side"));
        assertEquals(restored.get("wall"), defenderView.get("wall"));
        assertTrue(((Map<?, ?>) defenderView.get("attackerArmy")).isEmpty());
        var stale = command(marchId, 3, "wall");
        assertEquals(5, stale.get("round"));
        assertEquals(remaining, ((ConquestWallState) stale.get("wall")).hpRemaining());
        Map<String, Object> result = null;
        for (int r = 6; r <= 13; r++) {
            if (timeout) {
                var active = battles.findByMarchId(marchId).orElseThrow();
                marchService.processTimedOutTacticalBattle(active.getId(), active.getRoundDeadlineAt());
            } else result = command(marchId, r - 1, "wall");
            if (r < 13) assertTrue(battles.findByMarchId(marchId).isPresent());
        }
        if (!timeout) {
            assertEquals(true, result.get("finished"));
            assertEquals(13, result.get("round"));
            assertEquals(0, ((ConquestWallState) result.get("wall")).hpRemaining().signum());
        }
        assertTrue(battles.findByMarchId(marchId).isEmpty());
        assertTrue(marchRepository.findById(marchId).orElseThrow().getReturning());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(attackerId).get(0).getData());
        assertEquals(true, report.get("attackerWin"));
        assertEquals("WALL_BREACHED", report.get("terminalReason"));
        assertEquals(0, ((Number) report.get("exp")).intValue());
        assertTrue(((Map<?, ?>) report.get("initialDefender")).isEmpty());
        assertEquals(false, report.get("cityConquered"));
        assertEquals(defenderId, playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertEquals(5, buildingRepository.findById(wallBuildingId).orElseThrow().getLevel());
        marchService.processTimedOutTacticalBattle(sessionId, System.currentTimeMillis() + 600_000);
        assertEquals(1, scoutReportRepository.findByPlayerId(attackerId).size());
        assertEquals(1, scoutReportRepository.findByPlayerId(defenderId).size());
    }

    @Test
    void wallTechnologyFreezesAtOpeningAndNextBattleReadsNewLevel() {
        createTechnology(defenderId, "wall_reinforcement", 2);
        March first = start("conquer", 1);
        var wall = (ConquestWallState) marchService.getTacticalBattle(attackerId, first.getId()).get("wall");
        assertEquals(new BigDecimal("2750000.000000"), wall.maxHp());
        assertEquals(220, wall.defense());
        technologyRepository.findByPlayerIdAndType(defenderId, "wall_reinforcement").get(0).setLevel(10);
        battles.flush(); em.clear();
        var restored = (ConquestWallState) marchService.getTacticalBattle(attackerId, first.getId()).get("wall");
        assertEquals(wall, restored);
        var active = battles.findByMarchId(first.getId()).orElseThrow();
        active.setRoundNo(34); battles.saveAndFlush(active);
        assertEquals(true, command(first.getId(), 34, "wall").get("finished"));
        March next = start("conquer", 1);
        var reinforced = (ConquestWallState) marchService.getTacticalBattle(attackerId, next.getId()).get("wall");
        assertEquals(new BigDecimal("3750000.000000"), reinforced.maxHp());
        assertEquals(300, reinforced.defense());
    }

    @Test
    void failedBreachPersistsDefenderLossesWithoutLootOrTakeover() {
        var unit = new ArmyUnit(); unit.setPlayerId(defenderId); unit.setCitySlot(1); unit.setType("infantry"); unit.setCount(1);
        armyUnitRepository.save(unit);
        March m = start("conquer", 5000); Long marchId = m.getId();
        BattleSession session = battles.findByMarchId(marchId).orElseThrow();
        session.setRoundNo(34); session.setAttackerPositions(JsonUtil.toJson(Map.of("special", session.getInitialDistance())));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("infantry", session.getInitialDistance()))); battles.saveAndFlush(session);
        var finalView = command(marchId, 34, "infantry");
        assertEquals(true, finalView.get("finished")); assertEquals("WALL_NOT_BREACHED", finalView.get("terminalReason"));
        assertTrue(((ConquestWallState) finalView.get("wall")).standing());
        assertEquals(defenderId, playerCityRepository.findById(targetId).orElseThrow().getOwnerId());
        assertTrue(playerCityRepository.findById(targetId).orElseThrow().getMorale() >= 70, "守方胜利沿用原民心奖励，不得触发征服扣民心");
        assertEquals(0, armyUnitRepository.findById(unit.getId()).orElseThrow().getCount());
        assertEquals(1234, resourcesRepository.findByPlayerIdAndCitySlot(defenderId, 1).orElseThrow().getFood());
        var report = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(attackerId).get(0).getData());
        assertEquals(false, report.get("attackerWin"));
        assertTrue(((Map<?, ?>) report.get("survivorDefender")).isEmpty());
        assertFalse(((Map<?, ?>) report.get("initialDefender")).containsKey("wall"));
        assertTrue(((String) report.get("report")).contains("余伤攻击敌城墙"));
        assertEquals(10, buildingRepository.findById(wallBuildingId).orElseThrow().getLevel());
    }

    @Test
    void clearingFocusDropsDeadTroopTargetAndAcceptsWallNextRound() {
        var unit = new ArmyUnit(); unit.setPlayerId(defenderId); unit.setCitySlot(1); unit.setType("infantry"); unit.setCount(1);
        armyUnitRepository.save(unit);
        March m = start("conquer", 5000);
        var session = battles.findByMarchId(m.getId()).orElseThrow();
        session.setAttackerPositions(JsonUtil.toJson(Map.of("special", session.getInitialDistance())));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("infantry", session.getInitialDistance()))); battles.saveAndFlush(session);
        var next = command(m.getId(), 0, "infantry");
        assertEquals(ConquestWallState.ATTACK_WALL, next.get("battlePhase"));
        var orders = (Map<?, ?>) next.get("orders");
        assertNull(((com.wargame.service.BattleService.UnitOrder) orders.get("special")).focusTarget());
        next = command(m.getId(), 1, "wall");
        assertEquals("wall", ((com.wargame.service.BattleService.UnitOrder) ((Map<?, ?>) next.get("orders")).get("special")).focusTarget());
    }

    @Test
    void historicalSessionWithoutSnapshotFinishesWithoutAddingWall() {
        March m = start("conquer", 5000);
        var session = battles.findByMarchId(m.getId()).orElseThrow();
        session.setWallLevelSnapshot(null); session.setWallMaxHp(null); session.setWallHpRemaining(null); session.setWallDefense(null);
        session.setBattlePhase(null); session.setBattleRulesVersion(null); battles.saveAndFlush(session);
        var result = command(m.getId(), 0, null);
        assertEquals(true, result.get("finished")); assertNull(result.get("wall"));
        assertEquals("DEFENDERS_ELIMINATED", result.get("terminalReason"));
    }

    @Test
    void plunderDoesNotFreezeWallAndEachNewConquestStartsAtFullHealth() {
        March plunder = start("plunder", 5000);
        assertNull(marchService.getTacticalBattle(attackerId, plunder.getId()).get("wall"));
        assertEquals(true, command(plunder.getId(), 0, null).get("finished"));
        March conquest = start("conquer", 1);
        var active = battles.findByMarchId(conquest.getId()).orElseThrow();
        active.setRoundNo(34); active.setAttackerPositions(JsonUtil.toJson(Map.of("special", active.getInitialDistance())));
        battles.saveAndFlush(active);
        var failure = command(conquest.getId(), 34, "wall");
        assertEquals("WALL_NOT_BREACHED", failure.get("terminalReason"));
        March next = start("conquer", 5000);
        var wall = (ConquestWallState) marchService.getTacticalBattle(attackerId, next.getId()).get("wall");
        assertEquals(wall.maxHp(), wall.hpRemaining());
        assertEquals(10, wall.level());
    }
}
