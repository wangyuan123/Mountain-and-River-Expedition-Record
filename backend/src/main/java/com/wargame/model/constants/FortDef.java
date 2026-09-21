package com.wargame.model.constants;

import java.util.Map;

/**
 * 城防设施定义 - 对应 data.js 中 G.DATA.forts
 */
public record FortDef(
        String key,
        String name,
        String desc,
        int atkGround, int atkAir, int atkSea, int atkFort,
        double def,
        double hp,
        int range,
        int spd,
        Map<String, Integer> cost,
        String strongVs,
        String cat,
        boolean autoAdvance
) {
    /** 对部队的最高攻击仅供经验与概览战力估算；攻坚针对廉价工事单独定标，不计入该估值。 */
    public int peakTroopAttack() { return Math.max(atkGround, Math.max(atkAir, atkSea)); }

    public static final Map<String, FortDef> FORTS = Map.of(
            "bunker", new FortDef("bunker", "碉堡", "坚固掩体,反步兵,近程高血防",
                    8, 1, 1, 1, 24, 260, 1050, 0,
                    Map.of("steel", 80, "oil", 0, "rare", 0),
                    "infantry", "fort", false),
            "howitzer", new FortDef("howitzer", "榴弹炮", "远程压制,反步兵与建筑",
                    30, 1, 28, 30, 6, 80, 3950, 0,
                    Map.of("steel", 60, "oil", 10, "rare", 5),
                    "infantry", "fort", false),
            "antitank", new FortDef("antitank", "反坦克炮", "穿甲火力,反装甲",
                    42, 1, 36, 15, 8, 80, 3850, 0,
                    Map.of("steel", 70, "oil", 10, "rare", 10),
                    "ltank", "fort", false),
            "flak", new FortDef("flak", "防空炮", "对空火力,反空军",
                    12, 63, 10, 1, 8, 80, 1850, 0,
                    Map.of("steel", 55, "oil", 15, "rare", 15),
                    "fighter", "fort", false)
    );
}
