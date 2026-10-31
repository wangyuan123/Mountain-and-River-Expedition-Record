package com.wargame.service;

import com.wargame.model.constants.TechDef;
import com.wargame.model.constants.TechnologyRules;
import java.util.*;

/** 科技效果由服务器生成展示快照，客户端仅格式化服务端返回的文本。 */
public final class TechnologyPresentation {
    private TechnologyPresentation() {}

    public static Map<String, Object> effect(TechDef definition, int level) {
        int current = Math.max(0, Math.min(definition.maxLevel(), level));
        int next = Math.min(definition.maxLevel(), current + 1);
        String effect = definition.effect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("curStr", text(effect, current));
        result.put("nextStr", text(effect, next));
        String label = switch (effect) {
            case "firepower_all" -> "行动火力"; case "recon" -> "情报等级";
            case "radar" -> "首回合拦截先手值"; case "academy_chance" -> "五星名将整批概率加成";
            case "capture_permille" -> "俘获比例"; case "medical" -> "伤兵回收率";
            case "wall_stats" -> "城墙生命与防御"; case "train" -> "兵种生产速度";
            case "build" -> "建筑资源消耗与基础工期"; case "res_food" -> "城内粮食产量";
            case "res_steel" -> "城内钢铁产量"; case "res_oil" -> "城内石油产量";
            case "res_rare" -> "城内稀矿产量"; case "load" -> "全兵种负重";
            case "cap" -> "仓库掠夺保护额度"; default -> "加成";
        };
        result.put("effectLabel", label); result.put("currentLabel", "当前" + label);
        if ("recon".equals(effect)) {
            var intel = List.of("城防类型", "城防数量与等级", "精确守军", "守城将领", "建筑等级", "驻城将领人数", "账号科技", "可掠夺资源测算", "驻城将领档案", "战力评估");
            result.put("currentText", (current > 0 ? "城市：基础资源、" + String.join("、", intel.subList(0, current)) + "；野地：精确守军"
                    : "基础资源；城市与野地的模糊守军") + "；侦查交火先手+" + current * 5 + "点");
        }
        return result;
    }

    private static String text(String effect, int level) {
        return switch (effect) {
            case "firepower_all" -> "+" + level * TechnologyRules.effectPercent(effect) + "%（×" + String.format(Locale.ROOT, "%.2f", 1 + level / 20.0) + "）";
            case "recon" -> "情报等级 Lv." + level;
            case "radar" -> "+" + level * 2 + "点";
            case "academy_chance" -> "+" + String.format(Locale.ROOT, "%.1f", level * .3) + "个百分点";
            case "capture_permille" -> String.format(Locale.ROOT, "%.1f", level / 10.0) + "%";
            case "medical" -> level * TechnologyRules.effectPercent(effect) + "%";
            default -> (level * TechnologyRules.effectPercent(effect) > 0 ? "+" : "") + level * TechnologyRules.effectPercent(effect) + "%";
        };
    }
}
