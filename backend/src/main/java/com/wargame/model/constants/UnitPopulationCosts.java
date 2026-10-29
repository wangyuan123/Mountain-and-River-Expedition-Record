package com.wargame.model.constants;

import java.util.Map;

/** 调整前的人口单价仅用于历史队列和存量部队的返还，不用于新征召。 */
public final class UnitPopulationCosts {
    private UnitPopulationCosts() {}

    private static final Map<String, Integer> LEGACY = Map.ofEntries(
            Map.entry("infantry", 1), Map.entry("truck", 1), Map.entry("armored", 2),
            Map.entry("recon_car", 2), Map.entry("ltank", 2), Map.entry("htank", 4),
            Map.entry("assault", 2), Map.entry("rocket", 3), Map.entry("scout", 1),
            Map.entry("special", 2), Map.entry("fighter", 2), Map.entry("bomber", 3),
            Map.entry("transport", 2), Map.entry("destroyer", 3), Map.entry("sub", 3),
            Map.entry("battleship", 6), Map.entry("carrier", 8));

    public static int legacy(String type) {
        return LEGACY.getOrDefault(type, 0);
    }
}
