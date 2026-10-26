package com.wargame.service.bot;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BotPolicyTest {
    @Test
    void preservesReserveAcrossMultipleDispatches() {
        assertEquals(Map.of("infantry", 65), BotPolicy.expedition(Map.of("infantry", 100), 35, 500));
        assertTrue(BotPolicy.expedition(Map.of("infantry", 35), Map.of("infantry", 65), 35, 500).isEmpty());
        assertEquals(Map.of("infantry", 15), BotPolicy.expedition(Map.of("infantry", 50), Map.of("infantry", 50), 35, 500));
    }

    @Test
    void capsArmyAndKeepsReconAtHome() {
        assertEquals(Map.of("infantry", 500), BotPolicy.expedition(Map.of("infantry", 1000, "recon_car", 3), 35, 500));
        assertTrue(BotPolicy.expedition(Map.of("scout", 10, "recon_car", 3), 35, 500).isEmpty());
    }

    @Test
    void requiresThreefoldAdvantageAndRejectsUnknownUnits() {
        assertTrue(BotPolicy.favorable(Map.of("infantry", 30), Map.of("infantry", 10)));
        assertFalse(BotPolicy.favorable(Map.of("infantry", 29), Map.of("infantry", 10)));
        assertFalse(BotPolicy.favorable(Map.of("infantry", 3000), Map.of("unknown", 1)));
        assertFalse(BotPolicy.favorable(Map.of("unknown", 100), Map.of()));
        assertFalse(BotPolicy.favorable(Map.of(), Map.of()));
        assertFalse(BotPolicy.favorable(Map.of("infantry", 30), Map.of("infantry", -1)));
    }

    @Test
    void validatesSettings() {
        assertThrows(IllegalArgumentException.class, () -> new BotStore.Settings(true, false, 0, 1, 35, 2, 500));
        assertThrows(IllegalArgumentException.class, () -> new BotStore.Settings(true, false, 0, 45, 0, 2, 500));
        assertThrows(IllegalArgumentException.class, () -> new BotStore.Settings(true, false, 0, 45, 35, 6, 500));
    }
}
