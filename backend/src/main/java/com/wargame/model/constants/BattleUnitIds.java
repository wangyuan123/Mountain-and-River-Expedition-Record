package com.wargame.model.constants;

import java.util.LinkedHashMap;
import java.util.Map;

/** 协防编队保留来源别名，结算回库存时统一合并为基础兵种。 */
public final class BattleUnitIds {
    private BattleUnitIds() {}
    public static String base(String id) { return id == null ? "" : id.split("~", 2)[0]; }

    /** 合并援军及历史分组别名，避免伤兵、战俘和库存生成虚构兵种。 */
    public static Map<String, Integer> aggregate(Map<String, Integer> army) {
        Map<String, Integer> result = new LinkedHashMap<>();
        army.forEach((id, count) -> result.merge(base(id), count, Integer::sum));
        return result;
    }
}
