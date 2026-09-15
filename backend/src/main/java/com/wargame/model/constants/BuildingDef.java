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
        Integer airCap,
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
        m.put("command", new BuildingDef("command", "市政厅", "主城,决定其他建筑等级上限",
                Map.of("steel", 400, "food", 200), 1.6, "core", 1));
        m.put("house", new BuildingDef("house", "民居", "提供人口上限,每级+1200人口",
                Map.of("steel", 120, "food", 60), 1.5, "core", GameConstants.GROUP_SLOTS_ARMY_MAX,
                1200, null, null, null, null, null, null, null));
        m.put("factory", new BuildingDef("factory", "军工厂", "生产步兵/卡车/装甲车与战机",
                Map.of("steel", 240, "oil", 100), 1.6, "army", GameConstants.GROUP_SLOTS_ARMY_MAX));
        m.put("lightfactory", new BuildingDef("lightfactory", "轻工厂", "生产轻型坦克",
                Map.of("steel", 260, "oil", 110, "rare", 10), 1.6, "army", 1));
        m.put("heavyfactory", new BuildingDef("heavyfactory", "重工厂", "生产重型坦克/突击炮/火箭",
                Map.of("steel", 320, "oil", 140, "rare", 30), 1.6, "army", 1));
        m.put("airport", new BuildingDef("airport", "机场", "生产空军",
                Map.of("steel", 280, "oil", 120, "rare", 30), 1.6, "army", 1));
        m.put("port", new BuildingDef("port", "港口", "生产海军",
                Map.of("steel", 360, "oil", 160, "rare", 50), 1.7, "army", 1));
        m.put("academy", new BuildingDef("academy", "军校", "招募军官",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("staff", new BuildingDef("staff", "参谋部", "军官槽位与野地上限,带兵上限 +10%/级",
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
        m.put("depot", new BuildingDef("depot", "仓库", "提升资源上限,被掠夺时保护资源",
                Map.of("steel", 100), 1.5, "res", GameConstants.GROUP_SLOTS_ARMY_MAX,
                null, null, null, 1500, 1000, null, null, null));
        m.put("lab", new BuildingDef("lab", "科研中心", "解锁与加速科技研究",
                Map.of("steel", 200, "food", 100, "rare", 20), 1.6, "core", 1));
        m.put("radar", new BuildingDef("radar", "雷达站", "侦察野地与敌方兵力",
                Map.of("steel", 180, "oil", 60, "rare", 20), 1.6, "core", 1));
        m.put("wall", new BuildingDef("wall", "围墙", "城防,提升守城部队防御",
                Map.of("steel", 200, "food", 80), 1.5, "def", 1,
                null, null, null, null, null, 5, null, null));
        m.put("apron", new BuildingDef("apron", "停机坪", "空军调度,提升空军出击上限",
                Map.of("steel", 220, "oil", 80, "rare", 20), 1.6, "def", 1,
                null, null, null, null, null, null, 20, null));
        m.put("transit", new BuildingDef("transit", "运输站", "资源调度,全资源产出 +3%/级",
                Map.of("steel", 160, "food", 80), 1.6, "res", 1,
                null, null, null, null, null, null, null, 3));
        m.put("liaison", new BuildingDef("liaison", "联络中心", "外交,军官刷新更优质",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("exchange", new BuildingDef("exchange", "交易所", "资源互换,按比例转换资源",
                Map.of("steel", 180, "food", 100, "gold", 100), 1.5, "res", 1));
        BUILDINGS = Collections.unmodifiableMap(m);
    }
}
