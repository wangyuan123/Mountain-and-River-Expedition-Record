package com.wargame.util;

import com.wargame.model.entity.Player;

/** 与排行榜SQL使用相同的账号资格和真实时间封禁边界。 */
public final class LeaderboardEligibility {
    private LeaderboardEligibility() {}

    public static boolean eligible(Player player, long wallTime) {
        if (player == null || !player.isGameInitialized() || !"ACTIVE".equals(player.getAccountStatus())
                || player.getDisabled() != null && player.getDisabled() != 0) return false;
        return !"BANNED".equalsIgnoreCase(player.getBanStatus())
                || player.getBannedUntil() != null && player.getBannedUntil() > 0 && player.getBannedUntil() <= wallTime;
    }
}
