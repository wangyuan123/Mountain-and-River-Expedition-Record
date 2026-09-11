package com.wargame.model.constants;

import java.util.Map;

/**
 * 阵营定义 - 对应 data.js 中 G.DATA.factions
 */
public record FactionDef(
        String key,
        String name,
        String color
) {
    public static final Map<String, FactionDef> FACTIONS = Map.of(
            "allies", new FactionDef("allies", "同盟国", "#4aa3ff"),
            "axis",   new FactionDef("axis",   "轴心国", "#ff7a4a")
    );
}
