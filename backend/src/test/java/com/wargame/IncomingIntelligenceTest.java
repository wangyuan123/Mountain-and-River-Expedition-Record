package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.March;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.service.WebSocketPushService;
import com.wargame.service.WorldViewService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class IncomingIntelligenceTest extends BaseServiceTest {
    @Autowired WorldViewService views;
    @SpyBean WebSocketPushService pushes;
    private Player attacker;
    private Player defender;
    private PlayerCity city;

    @BeforeEach
    void setup() {
        attacker = createTestPlayer("invader", 30);
        defender = createTestPlayer("defender", 30);
        city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defender.getId());
        city.setName("守方主城");
        city.setX(100);
        city.setY(100);
        city.setArmy("{}");
        city.setForts("{}");
        city.setResources("{}");
        playerCityRepository.save(city);
        createArmyUnit(attacker.getId(), "infantry", 100);
    }

    private March dispatch() {
        return marchService.createDispatch(attacker.getId(), new DispatchRequest(
                "player", city.getId(), "plunder", Map.of("infantry", 10), null, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 500})
    void scoutSettlementPersistsAndPushesReportsToBothSides(int defendingScouts) {
        if (defendingScouts > 0) createArmyUnit(defender.getId(), "scout", defendingScouts);
        city.setArmy(JsonUtil.toJson(Map.of("scout", defendingScouts)));
        playerCityRepository.save(city);
        createTechnology(attacker.getId(), "recon_level", 5);
        long now = System.currentTimeMillis();
        createMarch(attacker.getId(), "player", String.valueOf(city.getId()),
                city.getName(), 10, 10, 100, 100, Map.of("scout", 50), "scout",
                now - 60000, now - 1000, false, false);

        marchService.processMarches(attacker.getId(), now);

        var attackerReports = scoutReportRepository.findByPlayerId(attacker.getId());
        var defenderReports = scoutReportRepository.findByPlayerId(defender.getId());
        assertEquals(1, attackerReports.size());
        assertEquals(1, defenderReports.size());
        var attackData = JsonUtil.parseObjMap(attackerReports.get(0).getData());
        var saved = defenderReports.get(0);
        var data = JsonUtil.parseObjMap(saved.getData());
        assertEquals("scout", saved.getType());
        assertEquals(0L, saved.getReadAt());
        assertEquals(1, scoutReportRepository.countUnreadByPlayerId(defender.getId()));
        assertEquals("defender", data.get("perspective"));
        assertEquals("invader", data.get("attackerName"));
        assertEquals(city.getName(), data.get("targetName"));
        assertEquals(100, data.get("x"));
        assertEquals(100, data.get("y"));
        assertEquals(defendingScouts, data.get("myScouts"));
        assertEquals(50, data.get("enemyScouts"));
        assertEquals(attackData.get("myLost"), data.get("enemyLost"));
        assertEquals(attackData.get("enemyLost"), data.get("myLost"));
        assertEquals(!(Boolean) attackData.get("showCityInfo"), data.get("intercepted"));
        assertEquals(defendingScouts == 500, data.get("intercepted"));
        for (String hidden : List.of("resources", "techs", "buildings", "officers", "reconLevel", "showCityInfo")) {
            assertFalse(data.containsKey(hidden), "守方报告不应包含出征方的内部情报: " + hidden);
        }
        verify(pushes).pushScoutReport(eq(defender.getId()), argThat(payload ->
                saved.getId().equals(payload.get("id")) && "scout".equals(payload.get("type"))));
        verify(pushes).pushScoutReport(eq(attacker.getId()), anyMap());

        marchService.processMarches(attacker.getId(), now);
        assertEquals(1, scoutReportRepository.findByPlayerId(defender.getId()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plunder", "scout"})
    @SuppressWarnings("unchecked")
    void realDispatchAppearsOutsideMapViewAndCancellationRemovesIt(String action) {
        String unit = "scout".equals(action) ? "scout" : "infantry";
        if ("scout".equals(action)) createArmyUnit(attacker.getId(), unit, 100);
        March march = marchService.createDispatch(attacker.getId(), new DispatchRequest(
                "player", city.getId(), action, Map.of(unit, 10), null, Map.of()));
        long now = System.currentTimeMillis();
        march.setStartAt(now - 20_000);
        march.setArriveAt(now + 40_000);
        marchRepository.save(march);
        var incoming = (List<Map<String, Object>>) views.getWorld(defender.getId(), 0, 0, 1).get("incoming");
        assertEquals(1, incoming.size());
        assertEquals("march-" + march.getId(), incoming.get(0).get("id"));
        assertEquals("invader", incoming.get(0).get("fromName"));
        assertEquals("守方主城", incoming.get(0).get("targetName"));
        assertEquals(100, incoming.get(0).get("targetX"));
        assertEquals(march.getArriveAt(), incoming.get(0).get("arriveAt"));
        assertEquals(Map.of(unit, 10), incoming.get(0).get("army"));
        assertTrue(views.getIncoming(attacker).isEmpty());
        assertTrue(incomingMarchRepository.findByTargetPlayerId(defender.getId()).isEmpty(),
                "不能额外生成一份会重复结算战斗的来袭任务");
        verify(pushes).pushIncomingAttack(eq(defender.getId()), argThat(data -> "started".equals(data.get("event"))));
        marchService.cancelMarch(attacker.getId(), march.getId());
        assertTrue(views.getIncoming(defender).isEmpty());
        var ownMarches = (List<Map<String, Object>>) views.getWorld(attacker.getId(), 0, 0, 1).get("marches");
        assertEquals(1, ownMarches.size(), "撤回后我军行军仍显示返城部队");
        assertEquals(true, ownMarches.get(0).get("returning"));
        assertEquals(Map.of(unit, 10), ownMarches.get(0).get("army"));
        marchService.processMarches(attacker.getId(), march.getArriveAt());
        assertTrue(marchRepository.findById(march.getId()).isEmpty());
        assertEquals(100, armyUnitRepository.findByPlayerIdAndType(attacker.getId(), unit).get(0).getCount());
        assertTrue(scoutReportRepository.findByPlayerId(attacker.getId()).isEmpty());
        assertTrue(scoutReportRepository.findByPlayerId(defender.getId()).isEmpty());
        verify(pushes).pushIncomingAttack(eq(defender.getId()), argThat(data -> "cancelled".equals(data.get("event"))));
    }

    @Test
    void resolvedAttackDoesNotRemainInIncomingList() {
        March march = dispatch();
        marchService.processMarches(attacker.getId(), march.getArriveAt() + 1);
        assertTrue(views.getIncoming(defender).isEmpty());
        verify(pushes).pushIncomingAttack(eq(defender.getId()), argThat(data -> "resolved".equals(data.get("event"))));
    }

    @Test
    void returningAndUnrelatedTargetsDoNotTriggerIncoming() {
        March march = dispatch();
        march.setReturning(true);
        marchRepository.save(march);
        assertTrue(views.getIncoming(defender).isEmpty());
        march.setReturning(false);
        march.setTargetKind("npc");
        marchRepository.save(march);
        assertTrue(views.getIncoming(defender).isEmpty());
    }

    @Test
    void defenderTickPublishesIncomingSnapshotIncludingRemoval() {
        March march = dispatch();
        tickService.tick(defender.getId());
        verify(pushes).pushTickUpdate(eq(defender.getId()), argThat(data ->
                data.get("incoming") instanceof List<?> list && list.size() == 1));
        marchService.cancelMarch(attacker.getId(), march.getId());
        defender.setLastTick(0L);
        playerRepository.save(defender);
        tickService.tick(defender.getId());
        verify(pushes).pushTickUpdate(eq(defender.getId()), argThat(data ->
                data.get("incoming") instanceof List<?> list && list.isEmpty()));
    }
}
