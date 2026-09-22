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
    private static final Map<String, Double> PER_LEVEL_RATES = Map.ofEntries(
            Map.entry("frenzy", 0.10),
            Map.entry("bulwark", 0.10),
            Map.entry("blitz", 0.06),
            Map.entry("suppress", 0.06),
            Map.entry("pierce", 0.06),
            Map.entry("leadership", 0.04),
            Map.entry("medic", 0.03),
            Map.entry("harvest", 0.10),
            Map.entry("construct", 0.04),
            Map.entry("finance", 0.04),
            Map.entry("research", 0.04),
            Map.entry("ration", 0.16),
            Map.entry("counter", 0.10),
            Map.entry("learn", 0.06),
            Map.entry("borrow_armor", 0.06)
    );

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
            Map.entry("counter",    new OfficerSkillDef("counter",    "绝境反击", "受击存活后在第3/6/9...回合进行反击，伤害为剩余兵力总伤害的10%/级（最高50%）", 5, "def")),
            Map.entry("learn",      new OfficerSkillDef("learn",      "师夷长技", "第3、6、9…回合触发：同名存活敌军对应攻击的6%/级，单次最高敌方同名兵种攻击30%，仅本回合主动攻击生效", 5, "atk")),
            Map.entry("borrow_armor", new OfficerSkillDef("borrow_armor", "借甲御敌", "第2、5、8…回合触发：同名存活敌军防御的6%/级，单次最高敌方同名兵种防御30%，仅本回合生效", 5, "def"))
    );

    /** 获取技能定义（自动兼容历史存量 supply 技能映射到 leadership） */
    public static OfficerSkillDef getSkill(String key) {
        if ("supply".equals(key)) return OFFICER_SKILLS.get("leadership");
        return OFFICER_SKILLS.get(key);
    }

    /** 按当前技能等级生成战报描述，避免把“每级”计算规则直接展示给玩家。 */
    public String descriptionAtLevel(int level) {
        double rate = PER_LEVEL_RATES.getOrDefault(key, 0.0);
        if (rate <= 0 || desc == null || desc.isBlank()) return desc;

        int actualLevel = Math.max(0, Math.min(max, level));
        String finalPercent = formatPercent(rate * actualLevel);
        return desc.replaceAll("\\d+(?:\\.\\d+)?%/级", finalPercent + "%")
                .replaceAll("，最高\\d+(?:\\.\\d+)?%", "")
                .replaceAll("（最高\\d+(?:\\.\\d+)?%）", "");
    }

    private static String formatPercent(double ratio) {
        double percent = ratio * 100.0;
        if (percent == Math.rint(percent)) return Long.toString(Math.round(percent));
        return String.format(java.util.Locale.ROOT, "%.2f", percent)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    /**
     * 判断两个技能能否同时被同一军官学习。
     *
     * 坚守阵地与借甲御敌在同一防御回合生效；二者互斥以避免临时防御叠加形成过高峰值。
     */
    public static boolean conflictsWith(String firstSkillId, String secondSkillId) {
        return ("bulwark".equals(firstSkillId) && "borrow_armor".equals(secondSkillId))
                || ("borrow_armor".equals(firstSkillId) && "bulwark".equals(secondSkillId));
    }
}
