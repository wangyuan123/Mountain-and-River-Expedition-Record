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
            Map.entry("counter", 0.20),
            Map.entry("learn", 0.06),
            Map.entry("borrow_armor", 0.06),
            Map.entry("ranging", 0.08),
            Map.entry("barrier", 0.02),
            Map.entry("last_stand", 0.04),
            Map.entry("execution", 0.05),
            Map.entry("smoke", 0.05),
            Map.entry("reprisal", 0.02),
            Map.entry("ricochet", 0.03),
            Map.entry("pursuit", 0.06)
    );

    public static final Map<String, OfficerSkillDef> OFFICER_SKILLS = Map.ofEntries(
            Map.entry("frenzy",     new OfficerSkillDef("frenzy",     "全军冲锋", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军攻击力额外+10%/级，最高50%，仅本回合生效；主动攻击、余伤攻击与反击均适用，不放大师夷长技追加的攻击", 5, "atk")),
            Map.entry("bulwark",    new OfficerSkillDef("bulwark",    "坚守阵地", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军防御力额外+10%/级，最高50%，仅本回合生效", 5, "def")),
            Map.entry("blitz",      new OfficerSkillDef("blitz",      "闪电突击", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），战场移动速度+6%/级，最高30%，仅本回合生效；新版玩家城市战按各将领所属编队独立起算，首次接战不追溯机动或重排先手，后续生效回合影响机动与先手。野地、侦察、演习及历史战斗沿用首次接战影响同射程先手的原规则。行动顺序按射程优先、同射程速度优先；射程与速度均相同时，奇数回合进攻方先手、偶数回合防守方先手", 5, "spd")),
            Map.entry("suppress",   new OfficerSkillDef("suppress",   "火力压制", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），降低敌方自身攻击力6%/级，最高30%，仅本回合生效，主动攻击、余伤攻击与反击均适用，不削减师夷长技追加的攻击", 5, "debuff")),
            Map.entry("pierce",     new OfficerSkillDef("pierce",     "破甲打击", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），无视敌方有效防御6%/级，最高30%，仅本回合生效，主动攻击、余伤攻击与反击均适用", 5, "pierce")),
            Map.entry("leadership", new OfficerSkillDef("leadership", "三军统帅", "指挥官任命时，带兵上限额外+4%/级，最高20%", 5, "mil")),
            Map.entry("medic",      new OfficerSkillDef("medic",      "战地急救", "战后伤兵额外回收+3%/级，最高15%", 5, "medic")),
            Map.entry("harvest",    new OfficerSkillDef("harvest",    "屯田增产", "市长任命时，基础资源产出额外+10%/级", 5, "logi")),
            Map.entry("construct",  new OfficerSkillDef("construct",  "工程营造", "市长任命时，建筑工期缩短4%/级，最高20%", 5, "logi")),
            Map.entry("finance",    new OfficerSkillDef("finance",    "精明理财", "市长任命时，黄金税收产出额外+4%/级", 5, "know")),
            Map.entry("research",   new OfficerSkillDef("research",   "格物致知", "市长任命时，科研速度提升4%/级",     5, "know")),
            Map.entry("ration",     new OfficerSkillDef("ration",     "军屯自给", "市长任命时，全城养兵耗粮降低16%/级，最高80%", 5, "logi")),
            Map.entry("counter",    new OfficerSkillDef("counter",    "绝境反击", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；受击存活且射程允许时，以当前剩余兵力总伤害的20%/级反击，最高100%；按反击兵种自身对应领域攻击与专项倍率结算，不继承来袭方倍率", 5, "def")),
            Map.entry("learn",      new OfficerSkillDef("learn",      "师夷长技", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；主动攻击时借用交火阶段开始时存活同名敌军对应攻击的6%/级，单次最高为敌军对应攻击的30%；参考值含攻击科技与军官综合军事属性，不复制专项倍率或临时技能，反击不生效", 5, "atk")),
            Map.entry("borrow_armor", new OfficerSkillDef("borrow_armor", "借甲御敌", "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；受击时借用交火阶段开始时存活同名敌军防御的6%/级，单次最高为敌军同名兵种防御的30%，仅本回合生效；参考值只含基础防御与防御科技，不包含军官属性或技能", 5, "def")),
            Map.entry("ranging", new OfficerSkillDef("ranging", "三轮校射", "同一兵种连续三个回合主动命中同一编队的同一兵种，第三次攻击额外造成8%/级伤害，最高40%；换目标或中断攻击清零，反击不计数", 5, "atk")),
            Map.entry("barrier", new OfficerSkillDef("barrier", "战地屏障", "首次接战回合及此后每隔两个回合，各兵种获得当前总生命值2%/级的屏障，最高10%；先吸收伤害再结算伤亡，仅本回合有效，不恢复士兵", 5, "def")),
            Map.entry("last_stand", new OfficerSkillDef("last_stand", "背水奋战", "每回合开始时，本编队某兵种剩余数量低于其开战数量50%，该兵种自身攻击额外+4%/级，最高20%；主动攻击和反击均适用，不放大师夷长技追加攻击", 5, "atk")),
            Map.entry("execution", new OfficerSkillDef("execution", "残阵收割", "攻击前目标剩余数量不高于其所属编队本兵种开战数量30%时，本次伤害额外+5%/级，最高25%；每次切换目标重新判断", 5, "atk")),
            Map.entry("smoke", new OfficerSkillDef("smoke", "烟幕遮断", "首次接战回合及此后每隔两个回合，各兵种首次主动命中的目标，下一回合战场移动速度降低5%/级，最高25%；多个烟幕取最高值，不影响射程、先手或大地图行军", 5, "debuff")),
            Map.entry("reprisal", new OfficerSkillDef("reprisal", "蓄势反攻", "首次接战回合及此后每隔两个回合，各兵种首次主动攻击追加此前两个回合实际承伤的2%/级，最高10%；追加伤害不超过本次普通攻击伤害40%，触发后清空，屏障吸收不计入", 5, "atk")),
            Map.entry("ricochet", new OfficerSkillDef("ricochet", "跳弹射击", "首次接战回合及此后每隔两个回合，各兵种首次主动攻击后，向射程内另一合法敌军追加3%/级攻击强度的跳弹，最高15%；优先距主目标最近者，按第二目标独立结算，不连锁触发追加效果", 5, "atk")),
            Map.entry("pursuit", new OfficerSkillDef("pursuit", "乘胜追击", "参与歼灭占敌方所属编队开战总人口至少5%的兵种后，下一回合移动速度+6%/级，最高30%，首次主动攻击伤害+3%/级，最高15%；同回合只触发一次，不影响大地图行军", 5, "spd"))
    );

    /** 内政、统帅和战后急救沿用各自用途，其余技能均可进入交火快照。 */
    public static java.util.Set<String> combatSkills() {
        return OFFICER_SKILLS.keySet().stream().filter(id -> !java.util.Set.of(
                "leadership", "medic", "harvest", "construct", "finance", "research", "ration").contains(id))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /** 获取技能定义（自动兼容历史存量 supply 技能映射到 leadership） */
    public static OfficerSkillDef getSkill(String key) {
        if ("supply".equals(key)) return OFFICER_SKILLS.get("leadership");
        return OFFICER_SKILLS.get(key);
    }

    /** 保留旧JSON结构兼容战报和存档，所有已学的正式技能统一为原最高等级。 */
    public static String fullLevelSkills(String json) {
        if (json == null || json.isBlank()) return json;
        var normalized = new java.util.ArrayList<Map<String, Object>>();
        for (var raw : com.wargame.util.JsonUtil.parseList(json)) {
            var skill = new java.util.LinkedHashMap<String, Object>(raw);
            var definition = getSkill(java.util.Objects.toString(raw.get("id"), ""));
            if (definition != null) skill.put("lv", definition.max());
            normalized.add(skill);
        }
        return com.wargame.util.JsonUtil.toJson(normalized);
    }

    /** 按当前技能等级生成战报描述，避免把“每级”计算规则直接展示给玩家。 */
    public String descriptionAtLevel(int level) {
        if (desc == null || desc.isBlank()) return desc;
        int actualLevel = Math.max(0, Math.min(max, level));
        double rate = PER_LEVEL_RATES.getOrDefault(key, 0.0);
        if (rate <= 0) return desc;
        String finalPercent = formatPercent(rate * actualLevel);
        String text = "pursuit".equals(key) ? desc.replace("6%/级", formatPercent(.06 * actualLevel) + "%")
                .replace("3%/级", formatPercent(.03 * actualLevel) + "%")
                : desc.replaceAll("\\d+(?:\\.\\d+)?%/级", finalPercent + "%");
        return text
                .replaceAll("，最高\\d+(?:\\.\\d+)?%", "")
                .replaceAll("（最高\\d+(?:\\.\\d+)?%）", "");
    }

    /** 战报摘要使用实际等级与统一系数，不让浏览器解析“每级”说明换算。 */
    public String bonusText(int level) {
        String label = switch (key) {
            case "frenzy" -> "全军攻击力 +";
            case "bulwark" -> "全军防御力 +";
            case "blitz" -> "战场移动速度 +";
            case "suppress" -> "敌方自身攻击力 -";
            case "pierce" -> "无视敌方有效防御 ";
            case "leadership" -> "带兵上限 +";
            case "medic" -> "伤兵额外回收 +";
            case "harvest" -> "基础资源产出 +";
            case "construct" -> "建筑工期 -";
            case "finance" -> "黄金税收产出 +";
            case "research" -> "科研速度 +";
            case "ration" -> "全城养兵耗粮 -";
            case "counter" -> "反击伤害为剩余兵力总伤害的 ";
            case "learn" -> "借用同名敌军攻击的 ";
            case "borrow_armor" -> "借用同名敌军防御的 ";
            case "ranging" -> "第三轮集火伤害提高 ";
            case "barrier" -> "当前总生命值屏障 ";
            case "last_stand" -> "残阵自身攻击提高 ";
            case "execution" -> "攻击残阵伤害提高 ";
            case "smoke" -> "下一回合机动速度降低 ";
            case "reprisal" -> "此前两回合承伤转化 ";
            case "ricochet" -> "第二目标攻击强度 ";
            case "pursuit" -> "歼敌后机动速度提高 ";
            default -> "技能加成 ";
        };
        return label + formatPercent(bonus(key, level)) + "%";
    }

    /** 服务端展示和技能效果读取同一系数表，历史 supply 归一到统帅。 */
    public static double bonus(String id, int level) {
        return PER_LEVEL_RATES.getOrDefault("supply".equals(id) ? "leadership" : id, 0.0) * Math.max(0, Math.min(5, level));
    }

    private static String formatPercent(double ratio) {
        double percent = ratio * 100.0;
        if (percent == Math.rint(percent)) return Long.toString(Math.round(percent));
        return String.format(java.util.Locale.ROOT, "%.2f", percent)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
