package com.wargame.model.constants;

import java.util.Map;

/**
 * 资源定义 - 对应 data.js 中 G.DATA.resources
 */
public record ResourceDef(
        String key,
        String name,
        String icon,
        int baseCap,
        double capGrowth
) {
    public static final Map<String, ResourceDef> RESOURCES = Map.of(
            "food",  new ResourceDef("food",  "粮食", "粮", 2000, 1.0),
            "steel", new ResourceDef("steel", "钢铁", "钢", 2000, 1.0),
            "oil",   new ResourceDef("oil",   "石油", "油", 1500, 0.9),
            "rare",  new ResourceDef("rare",  "稀矿", "稀", 800,  0.7),
            "gold",  new ResourceDef("gold",  "黄金", "金", 0,    0)
    );
}
