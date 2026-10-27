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

    private static final Map<String, ItemDef> BASE_ITEMS = Map.ofEntries(
            Map.entry("commanderRenameCard", new ItemDef("commanderRenameCard", "统帅改名卡", "img/items/commander-rename-card.svg", CAT_UTIL, "修改统帅名称，成功消耗1张；首次使用不限时间，之后每次间隔30天（现实时间）", 0)),
            // —— 军官道具 ——
            Map.entry("expBook",    new ItemDef("expBook",    "经验书",      "img/items/exp-book.svg", CAT_OFFICER, "军官使用,获得10000经验", 0)),
            Map.entry("expBookAdv", new ItemDef("expBookAdv", "高级经验书",  "img/items/exp-book-adv.svg", CAT_OFFICER, "每本提供100000经验，可批量使用", 0)),
            Map.entry("expBookMax", new ItemDef("expBookMax", "满级经验书",  "img/items/exp-book-max.svg", CAT_OFFICER, "使用1本即可将1～99级军官直接升至满级(Lv.100)，补足升级属性点", 0)),
            Map.entry("skillBook",  new ItemDef("skillBook",  "普通技能书",  "img/items/skill-book.svg", CAT_OFFICER, "军官使用，随机学习一个未掌握且不互斥的技能，初始等级受军官星级影响", 0)),
            Map.entry("universalSkillBook", new ItemDef("universalSkillBook", "通用技能书", "img/items/universal-skill-book.svg", CAT_OFFICER, "从技能池自选一个未掌握且不互斥的技能，学习后为 Lv.1；仅用于学习，不用于升级", 0)),
            // —— 指定技能书：直接学习对应技能 Lv.1 ——
            Map.entry("skillBook_frenzy", new ItemDef("skillBook_frenzy", "全军冲锋技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习全军冲锋 Lv.1", 0)),
            Map.entry("skillBook_bulwark", new ItemDef("skillBook_bulwark", "坚守阵地技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习坚守阵地 Lv.1", 0)),
            Map.entry("skillBook_blitz", new ItemDef("skillBook_blitz", "闪电突击技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习闪电突击 Lv.1", 0)),
            Map.entry("skillBook_suppress", new ItemDef("skillBook_suppress", "火力压制技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习火力压制 Lv.1", 0)),
            Map.entry("skillBook_pierce", new ItemDef("skillBook_pierce", "破甲打击技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习破甲打击 Lv.1", 0)),
            Map.entry("skillBook_leadership", new ItemDef("skillBook_leadership", "三军统帅技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习三军统帅 Lv.1", 0)),
            Map.entry("skillBook_medic", new ItemDef("skillBook_medic", "战地急救技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习战地急救 Lv.1", 0)),
            Map.entry("skillBook_harvest", new ItemDef("skillBook_harvest", "屯田增产技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习屯田增产 Lv.1", 0)),
            Map.entry("skillBook_construct", new ItemDef("skillBook_construct", "工程营造技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习工程营造 Lv.1", 0)),
            Map.entry("skillBook_finance", new ItemDef("skillBook_finance", "精明理财技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习精明理财 Lv.1", 0)),
            Map.entry("skillBook_research", new ItemDef("skillBook_research", "格物致知技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习格物致知 Lv.1", 0)),
            Map.entry("skillBook_ration", new ItemDef("skillBook_ration", "军屯自给技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习军屯自给 Lv.1", 0)),
            Map.entry("skillBook_counter", new ItemDef("skillBook_counter", "绝境反击技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习绝境反击 Lv.1", 0)),
            Map.entry("skillBook_learn", new ItemDef("skillBook_learn", "师夷长技技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习师夷长技 Lv.1", 0)),
            Map.entry("skillBook_borrow_armor", new ItemDef("skillBook_borrow_armor", "借甲御敌技能书", "img/items/skill-book.svg", CAT_OFFICER, "军官使用，直接学习借甲御敌 Lv.1", 0)),
            Map.entry("loyaltyBox", new ItemDef("loyaltyBox", "忠诚宝箱",    "img/items/loyalty-box.svg", CAT_OFFICER, "军官忠诚度+20", 0)),
            Map.entry("renameCard", new ItemDef("renameCard", "军官改名卡",  "img/items/rename-card.svg", CAT_OFFICER, "为军官更换新名字", 0)),
            Map.entry("recruitOrd", new ItemDef("recruitOrd", "征募令",      "img/items/recruit-ord.svg", CAT_OFFICER, "刷新军校,保底出现一名五星军官", 0)),
            Map.entry("starUp",     new ItemDef("starUp",     "星耀符",      "img/items/star-up.svg", CAT_OFFICER, "升2/3/4/5星失败率10/20/30/60%，失败也消耗1枚", 0)),
            // —— 资源道具 ——
            Map.entry("goldBox",    new ItemDef("goldBox",    "黄金箱",      "img/resources/models/gold.webp", CAT_RESOURCE, "开启获得5万黄金", 0)),
            Map.entry("resBox",     new ItemDef("resBox",     "资源箱",      "img/items/res-box.svg", CAT_RESOURCE, "开启获得粮钢油稀各5万", 0)),
            Map.entry("foodPack",   new ItemDef("foodPack",   "粮食大礼包",  "img/resources/models/food.webp", CAT_RESOURCE, "开启获得60万粮食", 0)),
            Map.entry("steelPack",  new ItemDef("steelPack",  "钢铁大礼包",  "img/resources/models/steel.webp", CAT_RESOURCE, "开启获得60万钢铁", 0)),
            Map.entry("oilPack",    new ItemDef("oilPack",    "石油大礼包",  "img/resources/models/oil.webp", CAT_RESOURCE, "开启获得60万石油", 0)),
            Map.entry("rarePack",   new ItemDef("rarePack",   "稀矿大礼包",  "img/resources/models/rare.webp", CAT_RESOURCE, "开启获得60万稀矿", 0)),
            Map.entry("supplyPack", new ItemDef("supplyPack", "战备补给包",  "img/resources/models/food.webp", CAT_RESOURCE, "开启获得粮钢油稀各40万", 0)),
            Map.entry("resourcePack100w", new ItemDef("resourcePack100w", "资源大礼包（100万）", "img/items/res-box.svg", CAT_RESOURCE, "粮食/钢铁/石油/稀矿各100万", 0)),
            Map.entry("resourcePack200w", new ItemDef("resourcePack200w", "资源大礼包（200万）", "img/items/res-box.svg", CAT_RESOURCE, "粮食/钢铁/石油/稀矿各200万", 0)),
            Map.entry("resourcePack350w", new ItemDef("resourcePack350w", "资源大礼包（350万）", "img/items/res-box.svg", CAT_RESOURCE, "粮食/钢铁/石油/稀矿各350万", 0)),
            Map.entry("resourcePack500w", new ItemDef("resourcePack500w", "资源大礼包", "img/items/resource-pack-500w.svg", CAT_RESOURCE, "粮食/钢铁/石油/稀矿各500万", 0)),
            Map.entry("newbiePack", new ItemDef("newbiePack", "新手礼包", "img/items/newbie-pack.svg", CAT_RESOURCE, "开启获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000", 0)),

            // —— 主线任务阶段通关专属大礼包 ——
            Map.entry("stage_gift_1", new ItemDef("stage_gift_1", "新兵奠基礼包", "🎁", CAT_RESOURCE, "主线第1阶段通关大奖：粮钢油各2万、稀矿5000、黄金2000、钻石50、10分加速符×5、列兵军事装备箱×1", 0)),
            Map.entry("stage_gift_2", new ItemDef("stage_gift_2", "拔寨起步礼包", "🎁", CAT_RESOURCE, "主线第2阶段通关大奖：粮钢油各5万、稀矿1.5万、黄金5000、钻石100、1时加速符×3、普通技能书×2", 0)),
            Map.entry("stage_gift_3", new ItemDef("stage_gift_3", "整军备战礼包", "🎁", CAT_RESOURCE, "主线第3阶段通关大奖：粮钢油各15万、稀矿5万、黄金1.2万、钻石200、1时加速符×5、征募令×2、校官军事装备箱×1", 0)),
            Map.entry("stage_gift_4", new ItemDef("stage_gift_4", "初露锋芒礼包", "🎁", CAT_RESOURCE, "主线第4阶段通关大奖：粮钢油各50万、稀矿15万、黄金3万、钻石500、5时加速符×3、护盾×1", 0)),
            Map.entry("stage_gift_5", new ItemDef("stage_gift_5", "百战精锐礼包", "🎁", CAT_RESOURCE, "主线第5阶段通关大奖：粮钢油各150万、稀矿50万、黄金8万、钻石1000、12时加速符×2、征募令×5、高级经验书×3", 0)),
            Map.entry("stage_gift_6", new ItemDef("stage_gift_6", "横扫千军礼包", "🎁", CAT_RESOURCE, "主线第6阶段通关大奖：粮钢油各500万、稀矿150万、黄金20万、钻石2000、24时加速符×2、高级经验书×5", 0)),
            Map.entry("stage_gift_7", new ItemDef("stage_gift_7", "威震四海礼包", "🎁", CAT_RESOURCE, "主线第7阶段通关大奖：粮钢油各1500万、稀矿500万、黄金50万、钻石5000、48时加速符×2、元帅军事装备箱×1", 0)),
            Map.entry("stage_gift_8", new ItemDef("stage_gift_8", "三军统帅大礼包", "👑", CAT_RESOURCE, "主线第8阶段终极大奖：粮钢油各5000万、稀矿1500万、黄金150万、钻石10000、72时加速符×3、满级经验书×2", 0)),

            // —— 功能道具 - 加速符（8 种时长）- 建筑施工/军队生产通用 ——
            Map.entry("speedUp10m",  new ItemDef("speedUp10m",  "10分加速符",  "img/items/speedup-10m.svg", CAT_UTIL, "立即缩短10分钟建筑/造兵时间",     10 * 60)),
            Map.entry("speedUp1h",   new ItemDef("speedUp1h",   "1时加速符",   "img/items/speedup-1h.svg", CAT_UTIL, "立即缩短1小时建筑/造兵时间",     60 * 60)),
            Map.entry("speedUp5h",   new ItemDef("speedUp5h",   "5时加速符",   "img/items/speedup-5h.svg", CAT_UTIL, "立即缩短5小时建筑/造兵时间",     5 * 60 * 60)),
            Map.entry("speedUp12h",  new ItemDef("speedUp12h",  "12时加速符",  "img/items/speedup-12h.svg", CAT_UTIL, "立即缩短12小时建筑/造兵时间",    12 * 60 * 60)),
            Map.entry("speedUp24h",  new ItemDef("speedUp24h",  "24时加速符",  "img/items/speedup-24h.svg", CAT_UTIL, "立即缩短24小时建筑/造兵时间",    24 * 60 * 60)),
            Map.entry("speedUp36h",  new ItemDef("speedUp36h",  "36时加速符",  "img/items/speedup-36h.svg", CAT_UTIL, "立即缩短36小时建筑/造兵时间",    36 * 60 * 60)),
            Map.entry("speedUp48h",  new ItemDef("speedUp48h",  "48时加速符",  "img/items/speedup-48h.svg", CAT_UTIL, "立即缩短48小时建筑/造兵时间",    48 * 60 * 60)),
            Map.entry("speedUp72h",  new ItemDef("speedUp72h",  "72时加速符",  "img/items/speedup-72h.svg", CAT_UTIL, "立即缩短72小时建筑/造兵时间",    72 * 60 * 60)),

            // —— 其他功能道具 ——
            Map.entry("teleportNormal", new ItemDef("teleportNormal", "普通传送", "img/items/teleport-normal.svg", CAT_UTIL, "消耗1个，将当前城市随机传送至开放地图的四格空闲陆地", 0)),
            Map.entry("teleportAdvanced", new ItemDef("teleportAdvanced", "高级传送", "img/items/teleport-advanced.svg", CAT_UTIL, "消耗1个，将当前城市传送至指定坐标，目的地须有完整2×2空闲陆地", 0)),
            Map.entry("shield2h", new ItemDef("shield2h", "2小时护盾", "img/items/shield-2h.svg", CAT_UTIL, "本城2小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地", 0)),
            Map.entry("shield", new ItemDef("shield", "8小时护盾", "img/items/shield-8h.svg", CAT_UTIL, "本城8小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地", 0)),
            Map.entry("shield12h", new ItemDef("shield12h", "12小时护盾", "img/items/shield-12h.svg", CAT_UTIL, "本城12小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地", 0)),
            Map.entry("shield24h", new ItemDef("shield24h", "24小时护盾", "img/items/shield-24h.svg", CAT_UTIL, "本城24小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地", 0)),
            Map.entry("shield72h", new ItemDef("shield72h", "72小时护盾", "img/items/shield-72h.svg", CAT_UTIL, "本城72小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地", 0)),
            Map.entry("marchOrd",  new ItemDef("marchOrd", "行军令", "img/items/march-ord.svg", CAT_UTIL, "仓库启用：行军速度+50%，持续1小时；情报使用：基础缩短剩余时间50%，指挥官学识提高效果，最高80%，至少保留30秒", 0)),
            Map.entry("signalJammingOrder", new ItemDef("signalJammingOrder", "通讯干扰令", "img/items/signal-jamming-order.svg", CAT_UTIL, "对来袭敌军使用：基础延长剩余时间50%，指挥官学识提高效果，最高70%", 0)),
            Map.entry("feintOrder", new ItemDef("feintOrder", "佯动诱敌令", "img/items/feint-order.svg", CAT_UTIL, "对从本城撤退的敌军使用：基础延长剩余时间50%，指挥官学识提高效果，最高70%", 0)),
            Map.entry("warSpeedOrder", new ItemDef("warSpeedOrder", "宣战加速令", "img/items/march-ord.svg", CAT_UTIL, "缩短本城发起的宣战备战时间：基础50%，学识提高效果，最高80%，总备战至少30分钟", 0)),
            Map.entry("populationOrder", new ItemDef("populationOrder", "人口动员令", "img/items/population-order.svg", CAT_UTIL, "使用后立即增加500空闲人口,不超过人口上限", 0)),
            Map.entry("emergencyPopulationOrder", new ItemDef("emergencyPopulationOrder", "紧急人口动员令", "img/items/emergency-population-order.svg", CAT_UTIL, "使用后立即增加当前城市人口容量上限30%的空闲人口,不超过人口上限", 0)),
            Map.entry("cityRenameCard", new ItemDef("cityRenameCard", "城市改名卡", "img/items/city-rename-card.svg", CAT_UTIL, "为一座城市更换新名字", 0))

    );

    /** 凭证与军衔共用定义，且不加入任何付费宝箱的产物列表。 */
    /** 护盾时长由服务端白名单决定，旧 shield 库存继续代表 8 小时，各档可混合顺延。 */
    public static final Map<String, Integer> SHIELD_HOURS = Map.of(
            "shield2h", 2, "shield", 8, "shield12h", 12, "shield24h", 24, "shield72h", 72);

    public static final Map<String, ItemDef> ITEMS;
    static {
        Map<String, ItemDef> items = new java.util.LinkedHashMap<>(BASE_ITEMS);
        for (var gear : OfficerEquipmentDef.ITEMS.values()) {
            items.put(gear.key(), new ItemDef(gear.key(), gear.name(), "🎖️", "equipment", gear.description(), 0));
            String box = "box_" + gear.setKey();
            items.putIfAbsent(box, new ItemDef(box, OfficerEquipmentDef.boxName(box), "img/items/officer-gear-box.svg", CAT_OFFICER,
                    "固定获得十二件装备，每部位一件；Lv." + gear.requiredLevel() + "可穿戴；同套3/6/9/12件共鸣", 0));
        }
        items.put("officerPreparationParts", new ItemDef("officerPreparationParts", "军备零件", "🧰", CAT_OFFICER,
                "用于军官部位整备；完成军备日常和周常获得", 0));
        for (var rank : MilitaryRankDef.RANKS) {
            if (rank.tier() == 1) continue;
            items.put(rank.certificateKey(), new ItemDef(rank.certificateKey(), rank.certificateName(), "🎖️",
                    CAT_UTIL, "仅用于晋升" + rank.name() + "，授衔消耗1张；" + MilitaryRankDef.certificateSource(rank), 0));
        }
        for (var equipment : UnitEquipmentDef.ITEMS.values()) {
            items.put(equipment.key(), new ItemDef(equipment.key(), equipment.name(), "🧰", "unitEquipment",
                    "本账号全部对应兵种生效；在军队页配置", 0));
        }
        ITEMS = Map.copyOf(items);
    }
}
