package com.wargame.model.constants;

import java.util.Map;

/**
 * 城防设施定义 - 对应 data.js 中 G.DATA.forts
 */
public record FortDef(
        String key,
        String name,
        String desc,
        int atk,
        int def,
        int hp,
        int range,
        int spd,
        Map<String, Integer> cost,
        String strongVs,
        String cat,
        boolean autoAdvance
) {
    public static final Map<String, FortDef> FORTS = Map.of(
            "bunker", new FortDef("bunker", "碉堡", "坚固掩体,反步兵,近程高血防",
                    8, 14, 260, 60, 0,
                    Map.of("steel", 30, "oil", 0, "rare", 0),
                    "infantry", "fort", false),
            "howitzer", new FortDef("howitzer", "榴弹炮", "远程压制,反步兵与建筑",
                    50, 6, 80, 300, 0,
                    Map.of("steel", 60, "oil", 10, "rare", 5),
                    "infantry", "fort", false),
            "antitank", new FortDef("antitank", "反坦克炮", "穿甲火力,反装甲",
                    45, 6, 70, 250, 0,
                    Map.of("steel", 70, "oil", 10, "rare", 10),
                    "ltank", "fort", false),
            "flak", new FortDef("flak", "防空炮", "对空火力,反空军",
                    35, 5, 60, 280, 0,
                    Map.of("steel", 55, "oil", 15, "rare", 15),
                    "fighter", "fort", false)
    );
}
