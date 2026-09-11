package com.wargame.model.constants;

import java.util.Map;

/**
 * 野地类型定义 - 对应 data.js 中 G.DATA.wildTypes
 *
 * res 为 null 表示该野地类型不携带资源（纯地形：森林/丘陵/沼泽），
 * 仍可占领但不能采集/掠夺资源。
 */
public record WildTypeDef(
        String key,
        String name,
        String icon,
        String res
) {
    public static final Map<String, WildTypeDef> WILD_TYPES = Map.ofEntries(
            Map.entry("forest",      new WildTypeDef("forest",      "森林",   "林", null)),
            Map.entry("hill",        new WildTypeDef("hill",        "丘陵",   "丘", null)),
            Map.entry("swamp",       new WildTypeDef("swamp",       "沼泽",   "沼", null)),
            Map.entry("grainfield",  new WildTypeDef("grainfield",  "粮田",   "粮", "food")),
            Map.entry("ironworks",   new WildTypeDef("ironworks",   "炼铁厂", "铁", "steel")),
            Map.entry("oil",         new WildTypeDef("oil",         "油田",   "油", "oil")),
            Map.entry("rarefactory", new WildTypeDef("rarefactory", "稀矿厂", "稀", "rare"))
    );
}
