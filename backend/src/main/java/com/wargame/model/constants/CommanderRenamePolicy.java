package com.wargame.model.constants;

import java.text.Normalizer;
import java.util.Locale;

/** 统帅用卡改名以真实时间计冷却，名称唯一键统一 NFC、首尾空格和大小写。 */
public final class CommanderRenamePolicy {
    public static final String ITEM_KEY = "commanderRenameCard";
    public static final long COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000;

    private CommanderRenamePolicy() {}

    public static long nextAllowedAt(long renamedAt) {
        return renamedAt <= 0 ? 0 : renamedAt + COOLDOWN_MS;
    }

    public static String nameKey(String name) {
        if (name == null) return null;
        return Normalizer.normalize(Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("^ +| +$", "")
                .toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
