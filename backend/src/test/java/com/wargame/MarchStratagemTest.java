package com.wargame;

import com.wargame.model.entity.March;
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
        assertTrue(delay >= 47_000L && delay <= 48_000L);
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
    void feintOrderUsesItsLargerDelayAndOneWayCountermeasureCannotBeRepeated() {
        March march = incoming();
        long arrival = march.getArriveAt();

        var result = marchService.applyMarchStratagem(defenderId, march.getId(), "feintOrder", arrival);

        long delay = ((Number) result.get("delayMs")).longValue();
        assertTrue(delay >= 95_000L && delay <= 96_000L);
        assertEquals(arrival + delay, marchRepository.findById(march.getId()).orElseThrow().getArriveAt());
        assertThrows(IllegalArgumentException.class, () -> marchService.applyMarchStratagem(
                defenderId, march.getId(), "signalJammingOrder", arrival + delay));
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
}
