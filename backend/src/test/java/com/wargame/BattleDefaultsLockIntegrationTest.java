package com.wargame;

import com.wargame.model.entity.March;
import com.wargame.model.entity.Player;
import com.wargame.service.BattleActionPreferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BattleDefaultsLockIntegrationTest extends BaseServiceTest {
    @Autowired private BattleActionPreferences preferences;

    @Test
    void onlyOutboundCombatMarchesLockTactics() {
        Player player = createTestPlayer("tactics-lock", 30);
        long now = System.currentTimeMillis();
        March march = createMarch(player.getId(), "wild", "1", "野地", 10, 10, 11, 10,
                Map.of("infantry", 1), "scout", now, now + 60000, false, false);
        assertFalse(preferences.get(player.getId()).locked());

        march.setAction("conquer");
        marchRepository.saveAndFlush(march);
        assertTrue(preferences.get(player.getId()).locked());
        var form = preferences.get(player.getId());
        assertThrows(IllegalArgumentException.class, () -> preferences.save(player.getId(),
                new BattleActionPreferences.Preferences(form.outgoing(), form.defending(), null)));

        march.setReturning(true);
        marchRepository.saveAndFlush(march);
        assertFalse(preferences.get(player.getId()).locked());
    }
}
