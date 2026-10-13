package com.wargame;
import com.wargame.model.entity.PvpMatchTicket;
import com.wargame.model.dto.pvp.PvpBattleData;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import com.wargame.service.pvp.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PvpRulesTest {
    private PvpMatchTicket ticket(long id, long power, int rating, long wait) {
        PvpMatchTicket t = new PvpMatchTicket(); t.setPlayerId(id); t.setPower(power); t.setRating(rating); t.setQueuedAt(1000000 - wait);
        t.setRegion("jiangsu-1"); t.setBattlefield("LAND_AIR"); t.setRulesVersion("v1"); t.setLeaseUntil(2000000); t.setStatus("QUEUED"); return t;
    }
    @Test void bothWindowsMustPermitPairingAndHardFiltersRemain() {
        var a = ticket(1, 10000, 1000, 120000); var b = ticket(2, 12000, 1150, 0);
        assertFalse(PvpPracticeService.eligible(a, b, 1000000)); b.setQueuedAt(1000000 - 60000); assertTrue(PvpPracticeService.eligible(a, b, 1000000));
        b.setRegion("other"); assertFalse(PvpPracticeService.eligible(a, b, 1000000)); b.setRegion(a.getRegion());
        b.setBattlefield("SEA_AIR"); assertFalse(PvpPracticeService.eligible(a, b, 1000000)); b.setBattlefield(a.getBattlefield());
        b.setPlacement(true); b.setQueuedAt(1000000 - 59999); assertFalse(PvpPracticeService.eligible(a, b, 1000000));
        b.setQueuedAt(1000000 - 60000); assertTrue(PvpPracticeService.eligible(a, b, 1000000));
        b.setLeaseUntil(1000000); assertFalse(PvpPracticeService.eligible(a, b, 1000000));
    }
    @Test void symmetricTimeoutDrawBoundaryAndElimination() {
        PvpBattleData d = new PvpBattleData(); d.setArmyA(Map.of("infantry", 1)); d.setArmyB(Map.of("infantry", 1)); d.setLossA(.4); d.setLossB(.45);
        assertEquals("DRAW", PvpPracticeService.result(d, 24)); d.setLossB(.451); assertEquals("A_WIN", PvpPracticeService.result(d, 24));
        d.setLossA(.7); assertEquals("B_WIN", PvpPracticeService.result(d, 24)); d.setArmyA(Map.of()); d.setArmyB(Map.of()); assertEquals("DRAW", PvpPracticeService.result(d, 1));
    }
    @Test void practiceEngineHasReproducibleStructuredEventsAndNoDefenderTimeout() {
        var ctx = new BattleService.TechCtx(Map.of(), Map.of("frenzy", 5, "counter", 3), 60, 60, CommandBonus.NONE);
        Map<String, Integer> army = Map.of("infantry", 100), left = Map.of("infantry", 0), right = Map.of("infantry", 0);
        var one = new BattleService(PvpPracticeService.roundSeed(42, 3)); var two = new BattleService(PvpPracticeService.roundSeed(42, 3));
        var a = one.resolvePracticeRound(army, army, left, right, 1000, ctx, ctx, 3, 0, Map.of(), Map.of());
        var b = two.resolvePracticeRound(army, army, left, right, 1000, ctx, ctx, 3, 0, Map.of(), Map.of());
        assertEquals(a, b); assertEquals(one.practiceEvents(), two.practiceEvents()); assertEquals(3, a.firstCombatRound());
        assertTrue(one.practiceEvents().stream().anyMatch(e -> "COUNTER".equals(e.get("type"))));
        assertTrue(one.practiceEvents().stream().anyMatch(e -> "OFFICER_ATTRIBUTES".equals(e.get("type")) && Boolean.TRUE.equals(e.get("active"))));
        var hold = Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD));
        var last = one.resolvePracticeRound(army, army, left, Map.of("infantry", 1000), 1000, ctx, ctx, 24, 0, hold, hold);
        assertTrue(last.finished()); assertFalse(last.log().contains("被迫撤退")); assertEquals(army, last.attackerArmy()); assertEquals(army, last.defenderArmy());
    }
}
