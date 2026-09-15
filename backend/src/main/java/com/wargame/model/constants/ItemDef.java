package com.wargame.model.constants;

import java.util.Map;

/**
 * 道具定义 - 对应 data.js 中 G.DATA.items
 *
 * <p>加速符类道具的 {@code speedUpSeconds} 表示使用后减少的施工剩余时间（秒）。
 * 后端通过 {@code itemKey} 查表获得秒数，避免前端伪造。
 */
public record ItemDef(
        String key,
        String name,
        String icon,
        String cat,
        String desc,
        int speedUpSeconds
) {

    /** cat: util - 功能道具 */
    public static final String CAT_UTIL = "util";
    /** cat: officer - 军官道具 */
    public static final String CAT_OFFICER = "officer";
    /** cat: resource - 资源道具 */
    public static final String CAT_RESOURCE = "resource";
    /** cat: jewelry - 珠宝珍品与宝箱 */
    public static final String CAT_JEWELRY = "jewelry";

    public static final Map<String, ItemDef> ITEMS = Map.ofEntries(
            // —— 军官道具 ——
            Map.entry("expBook",    new ItemDef("expBook",    "经验书",      "📘", CAT_OFFICER, "军官使用,获得10000经验", 0)),
            Map.entry("expBookAdv", new ItemDef("expBookAdv", "高级经验书",  "📕", CAT_OFFICER, "军官使用,获得100000经验", 0)),
            Map.entry("expBookMax", new ItemDef("expBookMax", "满级经验书",  "📙", CAT_OFFICER, "军官使用,直接升至满级(Lv.100)", 0)),
            Map.entry("skillBook",  new ItemDef("skillBook",  "技能书",      "📗", CAT_OFFICER, "为军官学习新技能", 0)),
            Map.entry("loyaltyBox", new ItemDef("loyaltyBox", "忠诚宝箱",    "🎁", CAT_OFFICER, "军官忠诚度+20", 0)),
            Map.entry("renameCard", new ItemDef("renameCard", "改名卡",      "🏷️", CAT_OFFICER, "为军官更换新名字", 0)),
            Map.entry("recruitOrd", new ItemDef("recruitOrd", "征募令",      "🎖️", CAT_OFFICER, "刷新军校,保底出现一名五星军官", 0)),
            Map.entry("starUp",     new ItemDef("starUp",     "星耀符",      "✨", CAT_OFFICER, "军官升星,属性大幅成长", 0)),
            // —— 军官装备宝箱（9 套专属整套装备宝箱）——
            Map.entry("box_recruit_military",  new ItemDef("box_recruit_military",  "列兵军事装备箱", "📦", CAT_OFFICER, "开启获得整套列兵军事装备(军刀/臂章/作训服)", 0)),
            Map.entry("box_recruit_logistics", new ItemDef("box_recruit_logistics", "列兵后勤装备箱", "📦", CAT_OFFICER, "开启获得整套列兵后勤装备(工具包/通行证/工作服)", 0)),
            Map.entry("box_recruit_knowledge", new ItemDef("box_recruit_knowledge", "列兵学识装备箱", "📦", CAT_OFFICER, "开启获得整套列兵学识装备(笔记本/学员章/学员服)", 0)),
            Map.entry("box_officer_military",  new ItemDef("box_officer_military",  "校官军事装备箱", "🎁", CAT_OFFICER, "开启获得整套校官军事装备(军刀/勋章/军服)", 0)),
            Map.entry("box_officer_logistics", new ItemDef("box_officer_logistics", "校官后勤装备箱", "🎁", CAT_OFFICER, "开启获得整套校官后勤装备(补给箱/调度章/军需服)", 0)),
            Map.entry("box_officer_knowledge", new ItemDef("box_officer_knowledge", "校官学识装备箱", "🎁", CAT_OFFICER, "开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)", 0)),
            Map.entry("box_marshal_military",  new ItemDef("box_marshal_military",  "元帅军事装备箱", "👑", CAT_OFFICER, "开启获得整套元帅军事装备(佩剑/将星/礼服)", 0)),
            Map.entry("box_marshal_logistics", new ItemDef("box_marshal_logistics", "元帅后勤装备箱", "👑", CAT_OFFICER, "开启获得整套元帅后勤装备(辎重车/军需印/长袍)", 0)),
            Map.entry("box_marshal_knowledge", new ItemDef("box_marshal_knowledge", "元帅学识装备箱", "👑", CAT_OFFICER, "开启获得整套元帅学识装备(望远镜/军师印/军礼服)", 0)),
            // —— 军官套装（3 阶 × 3 分支 × 3 槽位 = 27 件）——
            // —— 列兵套装 Lv.1+ ——
            Map.entry("recruit_military_weapon", new ItemDef("recruit_military_weapon", "列兵军刀",     "🗡️", CAT_OFFICER, "列兵军事套装·武器：军事+5，其余+1，集齐3件军事+3", 0)),
            Map.entry("recruit_military_badge",  new ItemDef("recruit_military_badge",  "列兵臂章",     "🎗️", CAT_OFFICER, "列兵军事套装·徽章：军事+5，其余+1，集齐3件军事+3", 0)),
            Map.entry("recruit_military_coat",   new ItemDef("recruit_military_coat",   "列兵作训服",   "🦺", CAT_OFFICER, "列兵军事套装·外套：军事+5，其余+1，集齐3件军事+3", 0)),
            Map.entry("recruit_logistics_weapon",new ItemDef("recruit_logistics_weapon","列兵工具包",   "🛠️", CAT_OFFICER, "列兵后勤套装·武器：后勤+5，其余+1，集齐3件后勤+3", 0)),
            Map.entry("recruit_logistics_badge", new ItemDef("recruit_logistics_badge", "列兵通行证",   "🪪", CAT_OFFICER, "列兵后勤套装·徽章：后勤+5，其余+1，集齐3件后勤+3", 0)),
            Map.entry("recruit_logistics_coat",  new ItemDef("recruit_logistics_coat",  "列兵工作服",   "👕", CAT_OFFICER, "列兵后勤套装·外套：后勤+5，其余+1，集齐3件后勤+3", 0)),
            Map.entry("recruit_knowledge_weapon",new ItemDef("recruit_knowledge_weapon","列兵笔记本",   "📓", CAT_OFFICER, "列兵学识套装·武器：学识+5，其余+1，集齐3件学识+3", 0)),
            Map.entry("recruit_knowledge_badge", new ItemDef("recruit_knowledge_badge", "列兵学员章",   "📛", CAT_OFFICER, "列兵学识套装·徽章：学识+5，其余+1，集齐3件学识+3", 0)),
            Map.entry("recruit_knowledge_coat",  new ItemDef("recruit_knowledge_coat",  "列兵学员服",   "🎓", CAT_OFFICER, "列兵学识套装·外套：学识+5，其余+1，集齐3件学识+3", 0)),

            // —— 校官套装 Lv.40+ ——
            Map.entry("officer_military_weapon", new ItemDef("officer_military_weapon", "校官军刀",     "⚔️", CAT_OFFICER, "校官军事套装·武器：军事+15，其余+3，集齐3件军事+15", 0)),
            Map.entry("officer_military_badge",  new ItemDef("officer_military_badge",  "校官勋章",     "🎖️", CAT_OFFICER, "校官军事套装·徽章：军事+15，其余+3，集齐3件军事+15", 0)),
            Map.entry("officer_military_coat",   new ItemDef("officer_military_coat",   "校官军服",     "🧥", CAT_OFFICER, "校官军事套装·外套：军事+15，其余+3，集齐3件军事+15", 0)),
            Map.entry("officer_logistics_weapon",new ItemDef("officer_logistics_weapon","校官补给箱",   "📦", CAT_OFFICER, "校官后勤套装·武器：后勤+15，其余+3，集齐3件后勤+15", 0)),
            Map.entry("officer_logistics_badge", new ItemDef("officer_logistics_badge", "校官调度章",   "🚚", CAT_OFFICER, "校官后勤套装·徽章：后勤+15，其余+3，集齐3件后勤+15", 0)),
            Map.entry("officer_logistics_coat",  new ItemDef("officer_logistics_coat",  "校官军需服",   "🦺", CAT_OFFICER, "校官后勤套装·外套：后勤+15，其余+3，集齐3件后勤+15", 0)),
            Map.entry("officer_knowledge_weapon",new ItemDef("officer_knowledge_weapon","校官战术罗盘", "🧭", CAT_OFFICER, "校官学识套装·武器：学识+15，其余+3，集齐3件学识+15", 0)),
            Map.entry("officer_knowledge_badge", new ItemDef("officer_knowledge_badge", "校官参谋章",   "📋", CAT_OFFICER, "校官学识套装·徽章：学识+15，其余+3，集齐3件学识+15", 0)),
            Map.entry("officer_knowledge_coat",  new ItemDef("officer_knowledge_coat",  "校官参谋服",   "🧑‍🎓", CAT_OFFICER, "校官学识套装·外套：学识+15，其余+3，集齐3件学识+15", 0)),

            // —— 元帅套装 Lv.100+ ——
            Map.entry("marshal_military_weapon", new ItemDef("marshal_military_weapon", "元帅佩剑",     "👑", CAT_OFFICER, "元帅军事套装·武器：军事+30，其余+5，集齐3件军事+30+全属性+5", 0)),
            Map.entry("marshal_military_badge",  new ItemDef("marshal_military_badge",  "元帅将星",     "⭐", CAT_OFFICER, "元帅军事套装·徽章：军事+30，其余+5，集齐3件军事+30+全属性+5", 0)),
            Map.entry("marshal_military_coat",   new ItemDef("marshal_military_coat",   "元帅礼服",     "🤴", CAT_OFFICER, "元帅军事套装·外套：军事+30，其余+5，集齐3件军事+30+全属性+5", 0)),
            Map.entry("marshal_logistics_weapon",new ItemDef("marshal_logistics_weapon","元帅辎重车",   "🐴", CAT_OFFICER, "元帅后勤套装·武器：后勤+30，其余+5，集齐3件后勤+30+全属性+5", 0)),
            Map.entry("marshal_logistics_badge", new ItemDef("marshal_logistics_badge", "元帅军需印",   "📜", CAT_OFFICER, "元帅后勤套装·徽章：后勤+30，其余+5，集齐3件后勤+30+全属性+5", 0)),
            Map.entry("marshal_logistics_coat",  new ItemDef("marshal_logistics_coat",  "元帅长袍",     "👘", CAT_OFFICER, "元帅后勤套装·外套：后勤+30，其余+5，集齐3件后勤+30+全属性+5", 0)),
            Map.entry("marshal_knowledge_weapon",new ItemDef("marshal_knowledge_weapon","元帅望远镜",   "🔭", CAT_OFFICER, "元帅学识套装·武器：学识+30，其余+5，集齐3件学识+30+全属性+5", 0)),
            Map.entry("marshal_knowledge_badge", new ItemDef("marshal_knowledge_badge", "元帅军师印",   "🪧", CAT_OFFICER, "元帅学识套装·徽章：学识+30，其余+5，集齐3件学识+30+全属性+5", 0)),
            Map.entry("marshal_knowledge_coat",  new ItemDef("marshal_knowledge_coat",  "元帅军礼服",   "👔", CAT_OFFICER, "元帅学识套装·外套：学识+30，其余+5，集齐3件学识+30+全属性+5", 0)),

            // —— 资源道具 ——
            Map.entry("goldBox",    new ItemDef("goldBox",    "黄金箱",      "🪙", CAT_RESOURCE, "开启获得1000-5000黄金", 0)),
            Map.entry("resBox",     new ItemDef("resBox",     "资源箱",      "📦", CAT_RESOURCE, "开启获得粮钢油稀各500", 0)),
            Map.entry("steelPack",  new ItemDef("steelPack",  "钢铁大礼包",  "🔩", CAT_RESOURCE, "立即获得20000钢铁", 0)),
            Map.entry("supplyPack", new ItemDef("supplyPack", "战备补给包",  "🌾", CAT_RESOURCE, "粮钢油稀各8000,适合长期发展", 0)),
            Map.entry("resourcePack500w", new ItemDef("resourcePack500w", "资源大礼包", "🎁", CAT_RESOURCE, "粮食/钢铁/石油/稀矿各500万", 0)),

            // —— 功能道具 - 加速符（8 种时长）- 建筑施工/军队生产通用 ——
            Map.entry("speedUp10m",  new ItemDef("speedUp10m",  "10分加速符",  "⚡", CAT_UTIL, "立即缩短10分钟建筑/造兵时间",     10 * 60)),
            Map.entry("speedUp1h",   new ItemDef("speedUp1h",   "1时加速符",   "⚡", CAT_UTIL, "立即缩短1小时建筑/造兵时间",     60 * 60)),
            Map.entry("speedUp5h",   new ItemDef("speedUp5h",   "5时加速符",   "⚡", CAT_UTIL, "立即缩短5小时建筑/造兵时间",     5 * 60 * 60)),
            Map.entry("speedUp12h",  new ItemDef("speedUp12h",  "12时加速符",  "⚡", CAT_UTIL, "立即缩短12小时建筑/造兵时间",    12 * 60 * 60)),
            Map.entry("speedUp24h",  new ItemDef("speedUp24h",  "24时加速符",  "⚡", CAT_UTIL, "立即缩短24小时建筑/造兵时间",    24 * 60 * 60)),
            Map.entry("speedUp36h",  new ItemDef("speedUp36h",  "36时加速符",  "⚡", CAT_UTIL, "立即缩短36小时建筑/造兵时间",    36 * 60 * 60)),
            Map.entry("speedUp48h",  new ItemDef("speedUp48h",  "48时加速符",  "⚡", CAT_UTIL, "立即缩短48小时建筑/造兵时间",    48 * 60 * 60)),
            Map.entry("speedUp72h",  new ItemDef("speedUp72h",  "72时加速符",  "⚡", CAT_UTIL, "立即缩短72小时建筑/造兵时间",    72 * 60 * 60)),

            // —— 其他功能道具 ——
            Map.entry("shield",    new ItemDef("shield",    "护盾",     "🛡️", CAT_UTIL, "使用后8小时免受玩家攻击", 0)),
            Map.entry("marchOrd",  new ItemDef("marchOrd",  "行军令",   "🚩", CAT_UTIL, "行军速度+50%,持续1小时", 0)),
            Map.entry("populationOrder", new ItemDef("populationOrder", "人口动员令", "👥", CAT_UTIL, "使用后立即增加500空闲人口,不超过人口上限", 0)),

            // —— 军衔珠宝宝箱 ——
            Map.entry("box_gem",          new ItemDef("box_gem",          "军衔珠宝宝箱",   "🗃️", CAT_JEWELRY, "开启获得晋升必备珠宝：珍珠×5、珊瑚×3、琉璃×3、琥珀×2、玛瑙×2", 0)),
            Map.entry("box_gem_primary",  new ItemDef("box_gem_primary",  "初级珠宝宝箱",   "🧰", CAT_JEWELRY, "开启获得士官晋升基础珠宝：珍珠×8、珊瑚×6、琉璃×5", 0)),
            Map.entry("box_gem_medium",   new ItemDef("box_gem_medium",   "中级珠宝宝箱",   "🧰", CAT_JEWELRY, "开启获得尉官晋升进阶珠宝：琥珀×8、玛瑙×6、水晶×5、翡翠×2", 0)),
            Map.entry("box_gem_senior",   new ItemDef("box_gem_senior",   "高级珠宝宝箱",   "🎁", CAT_JEWELRY, "开启获得校官晋升精选珠宝：水晶×8、翡翠×8、玉石×6、夜明珠×3", 0)),
            Map.entry("box_gem_supreme",  new ItemDef("box_gem_supreme",  "特级夜明珠宝箱", "🌟", CAT_JEWELRY, "开启获得将官晋升极品珍宝：夜明珠×8、玉石×10、翡翠×10", 0)),
            Map.entry("box_gem_grand",    new ItemDef("box_gem_grand",    "璀璨珠宝全集箱", "💎", CAT_JEWELRY, "开启获得全部9种晋升珠宝各5颗(共45颗珠宝)，助统帅连升数阶！", 0))
    );
}
