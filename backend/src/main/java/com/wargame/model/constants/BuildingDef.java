package com.wargame.model.constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 建筑定义 - 对应 data.js 中 G.DATA.buildings
 */
public record BuildingDef(
        String key,
        String name,
        String desc,
        Map<String, Integer> baseCost,
        double growth,
        String cat,
        Integer slots,
        Integer popPer,
        String produces,
        Integer baseProduce,
        Integer capPer,
        Integer protectPer,
        Integer defBonus,
        Integer airSpdBonus,
        Integer resBonus
) {
    /** 便捷构造器，用于没有可选字段的建筑 */
    public BuildingDef(String key, String name, String desc,
                       Map<String, Integer> baseCost, double growth, String cat, Integer slots) {
        this(key, name, desc, baseCost, growth, cat, slots, null, null, null, null, null, null, null, null);
    }

    public static final Map<String, BuildingDef> BUILDINGS;
    static {
        Map<String, BuildingDef> m = new HashMap<>();
        m.put("command", new BuildingDef("command", "前线指挥部", "基地核心中枢,决定其他建筑等级上限",
                Map.of("steel", 400, "food", 200), 1.6, "core", 1));
        m.put("house", new BuildingDef("house", "集结兵舍", "驻扎战备部队,提供人口与兵员上限,每级+1200",
                Map.of("steel", 120, "food", 60), 1.5, "core", GameConstants.GROUP_SLOTS_ARMY_MAX,
                1200, null, null, null, null, null, null, null));
        m.put("factory", new BuildingDef("factory", "战地兵工厂", "生产步兵、地面装备与侦察机",
                Map.of("steel", 240, "oil", 100), 1.6, "army", GameConstants.GROUP_SLOTS_ARMY_MAX));
        m.put("lightfactory", new BuildingDef("lightfactory", "轻装战车厂", "生产轻型坦克",
                Map.of("steel", 260, "oil", 110, "rare", 10), 1.6, "army", 1));
        m.put("heavyfactory", new BuildingDef("heavyfactory", "重装战车厂", "生产重型坦克",
                Map.of("steel", 320, "oil", 140, "rare", 30), 1.6, "army", 1));
        m.put("airport", new BuildingDef("airport", "空军基地", "生产空军战机",
                Map.of("steel", 280, "oil", 120, "rare", 30), 1.6, "army", 1));
        m.put("port", new BuildingDef("port", "军港船坞", "修造与停泊海军舰队",
                Map.of("steel", 360, "oil", 160, "rare", 50), 1.7, "army", 1));
        m.put("academy", new BuildingDef("academy", "陆军讲武堂", "培养招募军官,等级提升整批五星概率",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("staff", new BuildingDef("staff", "作战参谋部", "军官槽位与野地上限",
                Map.of("steel", 220, "food", 100), 1.6, "core", 1));
        m.put("farm", new BuildingDef("farm", "农田", "每小时产出粮食",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "food", 40, null, null, null, null, null));
        m.put("refinery", new BuildingDef("refinery", "炼钢厂", "每小时产出钢铁",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "steel", 40, null, null, null, null, null));
        m.put("oilfield", new BuildingDef("oilfield", "石油基地", "每小时产出石油",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "oil", 25, null, null, null, null, null));
        m.put("raremine", new BuildingDef("raremine", "稀矿厂", "每小时产出稀矿",
                Map.of("steel", 120, "oil", 40), 1.6, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "rare", 12, null, null, null, null, null));
        m.put("depot", new BuildingDef("depot", "军需物资库", "提升战备物资上限,被掠夺时保护资源",
                Map.of("steel", 100), 1.5, "res", GameConstants.GROUP_SLOTS_ARMY_MAX,
                null, null, null, 1500, 1000, null, null, null));
        m.put("lab", new BuildingDef("lab", "国防研究所", "解锁与加速科技研究",
                Map.of("steel", 200, "food", 100, "rare", 20), 1.6, "core", 1));
        m.put("radar", new BuildingDef("radar", "防空雷达站", "预警进犯敌军与探测兵力",
                Map.of("steel", 180, "oil", 60, "rare", 20), 1.6, "core", 1));
        m.put("wall", new BuildingDef("wall", "要塞防线", "基地外围防御要塞,提升守城部队防御,满级额外带兵上限+100000",
                Map.of("steel", 200, "food", 80), 1.5, "def", 1,
                null, null, null, null, null, 5, null, null));
        m.put("apron", new BuildingDef("apron", "战备起飞场", "空军调度阵位,空军全图航速+3%/级",
                Map.of("steel", 220, "oil", 80, "rare", 20), 1.6, "def", 1,
                null, null, null, null, null, null, 3, null));
        m.put("transit", new BuildingDef("transit", "战地兵站", "兵站后勤调度,全资源产出 +3%/级",
                Map.of("steel", 160, "food", 80), 1.6, "res", 1,
                null, null, null, null, null, null, null, 3));
        m.put("liaison", new BuildingDef("liaison", "机要通讯处", "盟军情报与外交联络",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("exchange", new BuildingDef("exchange", "交易所", "战备物资调配,按比例转换资源",
                Map.of("steel", 180, "food", 100, "gold", 100), 1.5, "res", 1));
        BUILDINGS = Collections.unmodifiableMap(m);
    }
}
