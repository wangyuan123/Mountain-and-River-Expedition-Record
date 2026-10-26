package com.wargame.util;

import com.wargame.model.entity.Player;

public final class PlayerNames {
    private PlayerNames() {}

    public static String commanderName(Player player) {
        return commanderName(player.getId(), player.getDisplayName());
    }

    public static String commanderName(Long playerId, String displayName) {
        return displayName == null || displayName.isBlank() ? "统帅" + playerId : displayName;
    }
}
