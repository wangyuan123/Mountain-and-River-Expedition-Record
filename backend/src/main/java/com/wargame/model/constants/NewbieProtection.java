package com.wargame.model.constants;

import com.wargame.model.entity.Player;

/** 新手保护独立于道具护盾，账号所有城市共享；时间均为服务端毫秒时间戳。 */
public final class NewbieProtection {
    public static final long DAY_MS = 86_400_000L;
    public static final int PRESTIGE_THRESHOLD = 10_000;
    public static final String CONFIRM_MESSAGE = "参与玩家战争将永久结束账号所有城市的新手保护，无法恢复。请确认退出保护后继续";

    private NewbieProtection() {}

    /** null 表示新账号尚未进入游戏；0 表示历史账号，不重新发放保护。 */
    public static void start(Player player, long now) {
        if (player.getNewbieProtectionStartedAt() == null && player.isGameInitialized()
                && !Boolean.TRUE.equals(player.getIsBot())) {
            player.setNewbieProtectionStartedAt(now);
        }
    }

    /** 前7天无条件保护，此后声望不足10000可延长至第14天；截止时刻立即失效。 */
    public static long until(Player player, long now) {
        if (player == null || player.isNewbieProtectionEnded() || Boolean.TRUE.equals(player.getIsBot())) return 0L;
        Long started = player.getNewbieProtectionStartedAt();
        if (started == null || started <= 0L) return 0L;
        long baseEnd = started + 7 * DAY_MS;
        long end = now < baseEnd || player.getPrestige() != null && player.getPrestige() >= PRESTIGE_THRESHOLD
                ? baseEnd : started + 14 * DAY_MS;
        return end > now ? end : 0L;
    }

    /** 声望在基础保护结束后达标即永久毕业，之后降低声望也不能重新获得保护。 */
    public static void finishForPrestige(Player player, long now) {
        Long started = player.getNewbieProtectionStartedAt();
        if (started != null && started > 0 && now >= started + 7 * DAY_MS
                && player.getPrestige() != null && player.getPrestige() >= PRESTIGE_THRESHOLD) {
            player.setNewbieProtectionEnded(true);
        }
    }

    /** 确认只校验意图，退出标记由调用方在操作全部校验成功后与战争操作一并提交。 */
    public static void requireConfirmation(Player player, Boolean confirmed, long now) {
        if (until(player, now) > now && !Boolean.TRUE.equals(confirmed)) {
            throw new com.wargame.security.GameAccessException("NEWBIE_PROTECTION_CONFIRM_REQUIRED", CONFIRM_MESSAGE, 409);
        }
    }
}
