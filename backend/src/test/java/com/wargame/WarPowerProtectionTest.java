package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.repository.PlayerPowerSnapshotRepository;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = "game.war-power.enabled=true")
class WarPowerProtectionTest extends BaseServiceTest {
    @Autowired WarPowerProtectionService protection;
    @Autowired PlayerPowerService power;
    @Autowired PlayerPowerSnapshotRepository snapshots;
    @Autowired WorldMapService maps;

    @Test void ratioIsSymmetricExactAndOverflowSafe() {
        assertTrue(WarPowerProtectionService.withinLimit(0, 0));
        assertFalse(WarPowerProtectionService.withinLimit(0, 1));
        assertTrue(WarPowerProtectionService.withinLimit(100, 1000));
        assertTrue(WarPowerProtectionService.withinLimit(1000, 100));
        assertFalse(WarPowerProtectionService.withinLimit(100, 1001));
        assertFalse(WarPowerProtectionService.withinLimit(Long.MAX_VALUE / 10, Long.MAX_VALUE));
        assertTrue(WarPowerProtectionService.withinLimit(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test void protectsBothDirectionsAndKeepsRecentPeakAfterArmyRemoved() {
        Player strong = createTestPlayer("war-power-strong", 30);
        Player weak = createTestPlayer("war-power-weak", 30);
        ArmyUnit army = createArmyUnit(strong.getId(), "infantry", 1000);
        createArmyUnit(weak.getId(), "infantry", 10);
        var high = power.calculate(strong.getId()).get(strong.getId());
        assertTrue(high.formationPower() > 0);
        assertFalse(protection.evaluate(strong.getId(), weak.getId()).allowed());
        assertFalse(protection.evaluate(weak.getId(), strong.getId()).allowed());
        army.setCount(10);
        armyUnitRepository.saveAndFlush(army);
        assertFalse(protection.evaluate(strong.getId(), weak.getId()).allowed());
        snapshots.deleteAll();
        snapshots.flush();
        assertTrue(protection.evaluate(strong.getId(), weak.getId()).allowed());
    }

    @Test void expiredSnapshotIsIgnoredAndMissingPlayerFailsClosed() {
        Player first = createTestPlayer("war-power-expired-first", 30);
        Player second = createTestPlayer("war-power-expired-second", 30);
        PlayerPowerSnapshot old = new PlayerPowerSnapshot();
        old.setPlayerId(first.getId());
        old.setObservedAt(GameClock.now() - PlayerPowerHistoryService.WINDOW_MS - 1);
        old.setFormationPower(1_000_000);
        old.setOverallPower(1_000_000);
        snapshots.saveAndFlush(old);
        assertTrue(protection.evaluate(first.getId(), second.getId()).allowed());
        assertFalse(protection.evaluate(first.getId(), Long.MAX_VALUE).allowed());
    }

    @Test void declarationMapAndInFlightMarchEnforceSameRule() {
        Player strong = createTestPlayer("war-power-attacker", 30);
        Player weak = createTestPlayer("war-power-defender", 30);
        long worldId = createTestWorld().getId();
        createArmyUnit(strong.getId(), "infantry", 1000);
        createArmyUnit(weak.getId(), "infantry", 10);
        PlayerCity city = new PlayerCity();
        city.setWorldId(worldId);
        city.setOwnerId(weak.getId());
        city.setCitySlot(0);
        city.setName("前线");
        city.setX(15);
        city.setY(15);
        city = playerCityRepository.saveAndFlush(city);
        assertEquals(false, worldService.declareWar(strong.getId(), city.getId()).get("success"));
        Map<?, ?> status = (Map<?, ?>) maps.target(strong.getId(), "player", city.getId()).get("powerProtection");
        assertEquals(false, status.get("allowed"));
        assertTrue(status.get("reason").toString().contains("10倍"));
        long now = GameClock.now();
        strong.setWarAgainstId(weak.getId());
        strong.setWarAt(now - 1);
        strong.setWarEndAt(now + 60_000);
        playerRepository.saveAndFlush(strong);
        var dispatch = new DispatchRequest("player", city.getId(), "plunder", Map.of("infantry", 10), null, null);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(strong.getId(), dispatch)).getMessage().contains("10倍"));
        March manual = createMarch(strong.getId(), "player", city.getId().toString(), city.getName(),
                10, 10, 15, 15, Map.of("infantry", 10), "plunder", now - 1000, now - 1, false, false);
        Map<String, Object> cancelled = marchService.getTacticalBattle(strong.getId(), manual.getId());
        assertEquals(true, cancelled.get("cancelled"));
        assertEquals("war_eligibility", cancelled.get("reason"));
        assertTrue(marchRepository.findById(manual.getId()).isEmpty());
        March march = createMarch(strong.getId(), "player", city.getId().toString(), city.getName(),
                10, 10, 15, 15, Map.of("infantry", 10), "plunder", now - 1000, now - 1, false, false);
        MarchService service = AopTestUtils.getTargetObject(marchService);
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "canAttackPlayerCity", strong.getId(), city, now));
        GuildRelationService original = (GuildRelationService) ReflectionTestUtils.getField(service, "guildRelations");
        GuildRelationService hostile = mock(GuildRelationService.class);
        when(hostile.areHostile(strong.getId(), weak.getId())).thenReturn(true);
        ReflectionTestUtils.setField(service, "guildRelations", hostile);
        try {
            assertEquals(false, ReflectionTestUtils.invokeMethod(service, "canAttackPlayerCity", strong.getId(), city, now));
            verify(hostile, never()).areHostile(anyLong(), anyLong());
        } finally {
            ReflectionTestUtils.setField(service, "guildRelations", original);
        }
        marchService.processMarches(strong.getId(), now);
        assertTrue(marchRepository.findById(march.getId()).isEmpty());
    }
}
