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
    public static final Map<String, OfficerSkillDef> OFFICER_SKILLS = Map.of(
            "frenzy",   new OfficerSkillDef("frenzy",   "猛攻",   "攻击力额外+10%/级",           5, "atk"),
            "bulwark",  new OfficerSkillDef("bulwark",  "铁壁",   "防御力额外+10%/级",           5, "def"),
            "blitz",    new OfficerSkillDef("blitz",    "闪电战", "行军速度+15%/级",             5, "spd"),
            "suppress", new OfficerSkillDef("suppress", "压制",   "降低敌方攻击力8%/级",         5, "debuff"),
            "pierce",   new OfficerSkillDef("pierce",   "破甲",   "无视敌方防御12%/级",          5, "pierce"),
            "supply",   new OfficerSkillDef("supply",   "补给",   "粮食消耗-20%/级",             5, "logi"),
            "medic",    new OfficerSkillDef("medic",    "急救",   "战后伤兵额外回收+3%/级，最高15%", 5, "medic"),
            "combo",    new OfficerSkillDef("combo",    "连击",   "8%/级概率额外攻击一次",       5, "combo")
    );
}
