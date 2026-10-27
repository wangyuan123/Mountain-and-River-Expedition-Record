package com.wargame;

import com.wargame.model.entity.March;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.WebSocketPushService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class MarchStratagemTest extends BaseServiceTest {
    @Autowired PlayerItemRepository items;
    @SpyBean WebSocketPushService pushes;
    private Long defenderId;
    private Long attackerId;
    private PlayerCity city;

    @BeforeEach
    void setUp() {
        defenderId = createTestPlayer("stratagem-defender", 30).getId();
        attackerId = createTestPlayer("stratagem-attacker", 30).getId();
        city = new PlayerCity();
        city.setWorldId(createTestWorld().getId());
        city.setOwnerId(defenderId);
        city.setName("军情目标城");
        city.setX(100);
        city.setY(100);
        city.setCitySlot(0);
        city.setArmy("{}");
        city.setForts("{}");
        city.setResources("{}");
        city = playerCityRepository.save(city);
        items.saveAndFlush(new PlayerItem(null, defenderId, "signalJammingOrder", 2, GameClock.now()));
        items.saveAndFlush(new PlayerItem(null, defenderId, "feintOrder", 1, GameClock.now()));
    }

    private March incoming() {
        long now = GameClock.now();
        March march = createMarch(attackerId, "player", city.getId().toString(), city.getName(),
                0, 0, 100, 100, Map.of("infantry", 100), "plunder", now - 60_000L,
                now + 240_000L, false, false);
        march.setRouteMode("land");
        march.setRouteData("[{\"x\":0,\"y\":0},{\"x\":100,\"y\":100}]");
        march.setBaseDurationMs(300_000L);
        return marchRepository.saveAndFlush(march);
    }

    @Test
    void signalJammingConsumesOneItemAndExtendsRouteWithoutJumpingPosition() {
        March march = incoming();
        long beforeStart = march.getStartAt(), beforeArrival = march.getArriveAt(), now = GameClock.now();
        double progress = (double) (now - beforeStart) / (beforeArrival - beforeStart);

        var result = marchService.applyMarchStratagem(defenderId, march.getId(), "signalJammingOrder", beforeArrival);

        March changed = marchRepository.findById(march.getId()).orElseThrow();
        long delay = ((Number) result.get("delayMs")).longValue();
        assertTrue(delay >= 119_000L && delay <= 120_000L);
        assertEquals(beforeArrival + delay, changed.getArriveAt());
        assertTrue(changed.getStratagemUsed());
        double afterProgress = (double) (now - changed.getStartAt()) / (changed.getArriveAt() - changed.getStartAt());
        assertEquals(progress, afterProgress, 0.01, "地图上的敌军当前位置不应跳变");
        assertEquals(1, items.findByPlayerIdAndItemKey(defenderId, "signalJammingOrder").orElseThrow().getCount());
        assertEquals(true, result.get("success"));
        assertEquals("signalJammingOrder", result.get("itemKey"));
        verify(pushes).pushIncomingAttack(eq(defenderId), argThat(data ->
                "stratagemDelayed".equals(data.get("event"))
                        && data.containsKey("arriveAt")
                        && !data.containsKey("army")
                        && !data.containsKey("totalTroops")));
        verify(pushes).pushMarchUpdate(eq(attackerId), argThat(data -> "stratagemDelayed".equals(data.get("event"))));
    }

    @Test
    void feintOrderOnlyDelaysRetreatAndCannotBeRepeatedInThatPhase() {
        March march = incoming();
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "feintOrder", march.getArriveAt()));
        march.setReturning(true);
        march.setStratagemUsed(true);
        marchRepository.saveAndFlush(march);
        long arrival = march.getArriveAt();

        var result = marchService.applyMarchStratagem(defenderId, march.getId(), "feintOrder", arrival);

        long delay = ((Number) result.get("delayMs")).longValue();
        assertTrue(delay >= 119_000L && delay <= 120_000L);
        assertEquals(arrival + delay, marchRepository.findById(march.getId()).orElseThrow().getArriveAt());
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "feintOrder", arrival + delay));
        assertTrue(marchRepository.findById(march.getId()).orElseThrow().getReturnStratagemUsed());
        assertEquals(2, items.findByPlayerIdAndItemKey(defenderId, "signalJammingOrder").orElseThrow().getCount());
        assertEquals(0, items.findByPlayerIdAndItemKey(defenderId, "feintOrder").orElseThrow().getCount());
    }

    @Test
    void staleArrivalsAndOtherCityTargetsAreRejectedBeforeConsumingItems() {
        March march = incoming();
        long arrival = march.getArriveAt();
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "signalJammingOrder", arrival - 1));
        city.setCitySlot(1);
        playerCityRepository.saveAndFlush(city);
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "signalJammingOrder", arrival));
        assertEquals(2, items.findByPlayerIdAndItemKey(defenderId, "signalJammingOrder").orElseThrow().getCount());
        assertFalse(marchRepository.findById(march.getId()).orElseThrow().getStratagemUsed());
    }

    @Test
    void knowledgeCapsLongDistanceDelayWithoutFixedMinuteLimit() {
        Officer commander = createOfficer(defenderId, "commander", 0, 0, 300);
        officerRepository.flush();
        March march = incoming();
        long arrival = GameClock.now() + 3_600_000L;
        march.setArriveAt(arrival);
        marchRepository.saveAndFlush(march);
        var preview = marchService.previewOrder(defenderId, march.getId(), "signalJammingOrder");
        assertEquals(300, preview.get("knowledge"));
        assertEquals(0.7, (double) preview.get("ratio"), 0.000001);
        var result = marchService.applyMarchStratagem(defenderId, march.getId(), "signalJammingOrder", arrival,
                (String) preview.get("confirmationToken"));
        assertTrue(((Number) result.get("delayMs")).longValue() > 2_500_000L);
        assertEquals(commander.getId(), ((Map<?, ?>) result.get("effect")).get("commanderId"));
    }

    @Test
    void changedCommanderKnowledgeInvalidatesPreviewWithoutConsuming() {
        Officer commander = createOfficer(defenderId, "commander", 0, 0, 100);
        officerRepository.flush();
        March march = incoming();
        var preview = marchService.previewOrder(defenderId, march.getId(), "signalJammingOrder");
        commander.setKnowledge(200);
        officerRepository.saveAndFlush(commander);
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "signalJammingOrder", march.getArriveAt(),
                (String) preview.get("confirmationToken")));
        assertEquals(2, items.findByPlayerIdAndItemKey(defenderId, "signalJammingOrder").orElseThrow().getCount());
    }
}
