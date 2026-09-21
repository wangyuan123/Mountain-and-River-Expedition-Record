package com.wargame.model.constants;

import java.util.Map;

/**
 * 军官技能定义 - 对应 data.js 中 G.DATA.officerSkills
 */
public record OfficerSkillDef(
        String key,
        String name,
        String desc,
        int max,
        String cat
) {
    public static final Map<String, OfficerSkillDef> OFFICER_SKILLS = Map.ofEntries(
            Map.entry("frenzy",     new OfficerSkillDef("frenzy",     "全军冲锋", "攻击力额外+10%/级，第1、4、7…回合触发", 5, "atk")),
            Map.entry("bulwark",    new OfficerSkillDef("bulwark",    "坚守阵地", "防御力额外+10%/级，第2、5、8…回合触发", 5, "def")),
            Map.entry("blitz",      new OfficerSkillDef("blitz",      "闪电突击", "战场移动速度+6%/级，最高30%",       5, "spd")),
            Map.entry("suppress",   new OfficerSkillDef("suppress",   "火力压制", "降低敌方攻击力6%/级，最高30%",       5, "debuff")),
            Map.entry("pierce",     new OfficerSkillDef("pierce",     "破甲打击", "无视敌方防御6%/级",           5, "pierce")),
            Map.entry("leadership", new OfficerSkillDef("leadership", "三军统帅", "指挥官任命时，带兵上限额外+4%/级，最高20%", 5, "mil")),
            Map.entry("medic",      new OfficerSkillDef("medic",      "战地急救", "战后伤兵额外回收+3%/级，最高15%", 5, "medic")),
            Map.entry("harvest",    new OfficerSkillDef("harvest",    "屯田增产", "市长任命时，基础资源产出额外+10%/级", 5, "logi")),
            Map.entry("construct",  new OfficerSkillDef("construct",  "工程营造", "市长任命时，建筑工期缩短4%/级，最高20%", 5, "logi")),
            Map.entry("finance",    new OfficerSkillDef("finance",    "精明理财", "市长任命时，黄金税收产出额外+4%/级", 5, "know")),
            Map.entry("research",   new OfficerSkillDef("research",   "格物致知", "市长任命时，科研速度提升4%/级",     5, "know")),
            Map.entry("ration",     new OfficerSkillDef("ration",     "军屯自给", "市长任命时，全城养兵耗粮降低16%/级，最高80%", 5, "logi")),
            Map.entry("counter",    new OfficerSkillDef("counter",    "绝境反击", "受击存活后在第3/6/9...回合进行反击，伤害为剩余兵力总伤害的10%/级（最高50%）", 5, "def"))
    );

    /** 获取技能定义（自动兼容历史存量 supply 技能映射到 leadership） */
    public static OfficerSkillDef getSkill(String key) {
        if ("supply".equals(key)) return OFFICER_SKILLS.get("leadership");
        return OFFICER_SKILLS.get(key);
    }
}
