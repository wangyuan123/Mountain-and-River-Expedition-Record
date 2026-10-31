package com.wargame.service;

import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.entity.Officer;
import com.wargame.util.JsonUtil;
import java.util.*;

/** 战报只保存参战属性与技能，不携带军官归属、装备明细或数据库身份。 */
public final class CommanderReportSnapshot {
    private CommanderReportSnapshot() {}

    public static Map<String, Object> from(Officer officer, EquipmentService.Attributes attributes) {
        if (officer == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", Objects.requireNonNullElse(officer.getName(), "未命名将领"));
        result.put("level", Objects.requireNonNullElse(officer.getLevel(), 0));
        result.put("baseMilitary", Objects.requireNonNullElse(officer.getMilitary(), 0));
        result.put("military", attributes.military());
        result.put("baseDefense", Objects.requireNonNullElse(officer.getDefense(), 0));
        result.put("defense", attributes.defense());
        Map<String, Integer> skills = new LinkedHashMap<>();
        for (Map<String, Object> raw : JsonUtil.parseList(officer.getSkills())) {
            if (raw.get("id") != null && raw.get("lv") instanceof Number level)
                skills.put(raw.get("id").toString(), level.intValue());
        }
        result.put("skills", skills(skills));
        return result;
    }

    /** 技能描述与等级一并冻结，历史显示不再读取玩家后来学会的技能。 */
    public static List<Map<String, Object>> skills(Map<String, Integer> levels) {
        List<Map<String, Object>> result = new ArrayList<>();
        levels.forEach((id, level) -> {
            OfficerSkillDef definition = OfficerSkillDef.getSkill(id);
            result.add(Map.of("id", id, "name", definition == null ? id : definition.name(), "level", level,
                    "description", definition == null ? "" : definition.descriptionAtLevel(level),
                    "bonusText", definition == null ? "" : definition.bonusText(level)));
        });
        return result;
    }
}
