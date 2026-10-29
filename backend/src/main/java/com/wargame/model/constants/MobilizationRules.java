package com.wargame.model.constants;

import com.wargame.model.entity.Player;
import com.wargame.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/** 三档账号动员独立计时，同档续时、异档取最高；到期比较以服务端时钟为准。 */
public final class MobilizationRules {
    public static final long DURATION_MS = 24 * 3600_000L;
    private MobilizationRules() {}

    public static Map<String, Long> expiries(Player player) {
        Map<String, Object> stored = JsonUtil.parseObjMap(player == null ? null : player.getMobilizationExpiries());
        Map<String, Long> result = new LinkedHashMap<>();
        for (String item : new String[]{"mobilizationOrder", "advancedMobilizationOrder", "rapidMobilizationOrder"}) {
            Object value = stored.get(item);
            result.put(item, value instanceof Number number ? Math.max(0L, number.longValue()) : 0L);
        }
        return result;
    }

    public static int bonus(Player player, long now) {
        return expiries(player).entrySet().stream().filter(entry -> entry.getValue() > now)
                .mapToInt(entry -> ItemDef.MOBILIZATION_BONUS_PERCENT.get(entry.getKey())).max().orElse(0);
    }

    /** 配置标识跨续期不变，整轮动员结束后失效，防止过期草稿覆盖默认编队。 */
    public static String formationKey(Player player, long now) {
        return bonus(player, now) > 0 ? "mobilization:" + player.getMobilizationCycle() : "default";
    }

    public static String sortieArmy(Player player, long now) {
        return bonus(player, now) > 0 ? player.getMobilizationSortieArmy() : player.getSortieArmy();
    }

    public static int apply(int ordinaryCap, int bonus) {
        return (int) Math.min(Integer.MAX_VALUE, (long) ordinaryCap * (100 + bonus) / 100);
    }
}
