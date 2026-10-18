package com.wargame;

import com.wargame.model.constants.NewbieProtection;
import com.wargame.model.entity.Player;
import com.wargame.security.GameAccessException;
import org.junit.jupiter.api.Test;

import static com.wargame.model.constants.NewbieProtection.DAY_MS;
import static org.junit.jupiter.api.Assertions.*;

class NewbieProtectionRulesTest {
    private static final long START = 1_800_000_000_000L;

    @Test
    void firstSevenDaysProtectEvenHighPrestigeAndEndExactlyAtBoundary() {
        Player player = protectedPlayer(10000);
        assertEquals(START + 7 * DAY_MS, NewbieProtection.until(player, START));
        assertEquals(START + 7 * DAY_MS, NewbieProtection.until(player, START + 7 * DAY_MS - 1));
        assertEquals(0L, NewbieProtection.until(player, START + 7 * DAY_MS));
    }

    @Test
    void lowPrestigeExtendsAtDaySevenButNeverBeyondDayFourteen() {
        Player player = protectedPlayer(9999);
        assertEquals(START + 14 * DAY_MS, NewbieProtection.until(player, START + 7 * DAY_MS));
        assertEquals(START + 14 * DAY_MS, NewbieProtection.until(player, START + 14 * DAY_MS - 1));
        assertEquals(0L, NewbieProtection.until(player, START + 14 * DAY_MS));
        player.setPrestige(10000);
        assertEquals(0L, NewbieProtection.until(player, START + 8 * DAY_MS));
    }

    @Test
    void firstEntryIsIdempotentAndSkipsHistoricalAccountsBotsAndUninitializedGames() {
        Player player = new Player();
        NewbieProtection.start(player, START);
        assertEquals(0L, player.getNewbieProtectionStartedAt());
        player.setNewbieProtectionStartedAt(null);
        player.setGameInitialized(false);
        NewbieProtection.start(player, START);
        assertNull(player.getNewbieProtectionStartedAt());
        player.setGameInitialized(true);
        player.setIsBot(true);
        NewbieProtection.start(player, START);
        assertNull(player.getNewbieProtectionStartedAt());
        player.setIsBot(false);
        NewbieProtection.start(player, START);
        NewbieProtection.start(player, START + DAY_MS);
        assertEquals(START, player.getNewbieProtectionStartedAt());
    }

    @Test
    void prestigeGraduationAndVoluntaryExitCannotBeReversedByLowerPrestige() {
        Player player = new Player();
        player.setPrestige(9999);
        player.setNewbieProtectionStartedAt(System.currentTimeMillis() - 8 * DAY_MS);
        player.setPrestige(10000);
        player.setPrestige(10);
        assertTrue(player.isNewbieProtectionEnded());
        assertEquals(0L, NewbieProtection.until(player, System.currentTimeMillis()));
        // 第7天前已达标，第7天后第一次变化就是降声望，也不能重新领取。
        player = new Player();
        player.setPrestige(10000);
        player.setNewbieProtectionStartedAt(System.currentTimeMillis() - 8 * DAY_MS);
        player.setPrestige(9999);
        assertTrue(player.isNewbieProtectionEnded());
        Player exited = protectedPlayer(0);
        exited.setNewbieProtectionEnded(true);
        NewbieProtection.start(exited, START + DAY_MS);
        assertEquals(0L, NewbieProtection.until(exited, START + DAY_MS));
    }

    @Test
    void confirmationOnlyChecksIntentAndDoesNotConsumeProtection() {
        Player player = protectedPlayer(0);
        GameAccessException error = assertThrows(GameAccessException.class,
                () -> NewbieProtection.requireConfirmation(player, null, START));
        assertEquals("NEWBIE_PROTECTION_CONFIRM_REQUIRED", error.getCode());
        assertEquals(409, error.getStatus());
        NewbieProtection.requireConfirmation(player, true, START);
        assertFalse(player.isNewbieProtectionEnded());
    }

    private Player protectedPlayer(int prestige) {
        Player player = new Player();
        player.setPrestige(prestige);
        player.setNewbieProtectionStartedAt(START);
        return player;
    }
}
