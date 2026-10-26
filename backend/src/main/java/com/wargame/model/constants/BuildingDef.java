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
        Integer airSpdBonus,
        Integer resBonus
) {
    /** 墙体成长只作用于真实城市征服目标，不对守军提供倍率。 */
    @com.fasterxml.jackson.annotation.JsonProperty
    public Integer wallHpPerLevel() { return "wall".equals(key) ? com.wargame.model.dto.ConquestWallState.HP_PER_LEVEL : null; }
    @com.fasterxml.jackson.annotation.JsonProperty
    public Integer wallDefensePerLevel() { return "wall".equals(key) ? com.wargame.model.dto.ConquestWallState.DEFENSE_PER_LEVEL : null; }
    /** 便捷构造器，用于没有可选字段的建筑 */
    public BuildingDef(String key, String name, String desc,
                       Map<String, Integer> baseCost, double growth, String cat, Integer slots) {
        this(key, name, desc, baseCost, growth, cat, slots, null, null, null, null, null, null, null);
    }

    public static final Map<String, BuildingDef> BUILDINGS;
    static {
        Map<String, BuildingDef> m = new HashMap<>();
        m.put("command", new BuildingDef("command", "市政厅", "城市枢纽，决定建筑等级与本城野地上限（基础5＋等级）",
                Map.of("steel", 400, "food", 200), 1.6, "core", 1));
        m.put("house", new BuildingDef("house", "民居", "每级增加1200平民人口容量，平民可用于征兵；不限制已有军队数量或单队带兵上限",
                Map.of("steel", 120, "food", 60), 1.5, "core", GameConstants.GROUP_SLOTS_ARMY_MAX,
                1200, null, null, null, null, null, null));
        m.put("factory", new BuildingDef("factory", "军工厂", "生产步兵、卡车、装甲车、侦查装甲车、突击炮、反坦克火箭与特种兵；等级提高生产速度与并行数",
                Map.of("steel", 240, "oil", 100), 1.6, "army", GameConstants.GROUP_SLOTS_ARMY_MAX));
        m.put("lightfactory", new BuildingDef("lightfactory", "轻装战车厂", "生产轻型坦克；等级提高生产速度与并行数",
                Map.of("steel", 260, "oil", 110, "rare", 10), 1.6, "army", 1));
        m.put("heavyfactory", new BuildingDef("heavyfactory", "重装战车厂", "生产重型坦克；等级提高生产速度与并行数",
                Map.of("steel", 320, "oil", 140, "rare", 30), 1.6, "army", 1));
        m.put("airport", new BuildingDef("airport", "空军基地", "生产侦察机、战斗机、轰炸机和运输机；等级提高生产速度与并行数",
                Map.of("steel", 280, "oil", 120, "rare", 30), 1.6, "army", 1));
        m.put("port", new BuildingDef("port", "军港", "沿海城市海军配套设施；本城造舰同时需要军港与造船厂，军港不直接加速造舰",
                Map.of("steel", 360, "oil", 160, "rare", 50), 1.7, "army", 1));
        m.put("shipyard", new BuildingDef("shipyard", "造船厂", "建造海军舰船，等级提升生产速度与并行数，需本城军港",
                Map.of("steel", 400, "oil", 180, "rare", 60), 1.7, "army", 1));
        m.put("academy", new BuildingDef("academy", "军校", "招募军官；普通刷新整批出现五星的基础概率每级增加0.3个百分点，可与军事理论相加，每批最多1名五星",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("staff", new BuildingDef("staff", "作战参谋部", "军事指挥、军官管理与高级科技",
                Map.of("steel", 220, "food", 100), 1.6, "core", 1));
        m.put("headquarters", new BuildingDef("headquarters", "司令部", "任命司令官与提升出征队伍上限(基础5支+每级1支)，四项属性增益本城全部战斗",
                Map.of("steel", 300, "food", 150, "gold", 150), 1.6, "core", 1));
        // 一级基准产出：粮食/钢铁均为600/小时，石油/稀矿均为500/小时。
        m.put("farm", new BuildingDef("farm", "农田", "每小时产出粮食",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "food", 600, null, null, null, null));
        m.put("refinery", new BuildingDef("refinery", "炼钢厂", "每小时产出钢铁",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "steel", 600, null, null, null, null));
        m.put("oilfield", new BuildingDef("oilfield", "石油基地", "每小时产出石油",
                Map.of("steel", 80), 1.5, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "oil", 500, null, null, null, null));
        m.put("raremine", new BuildingDef("raremine", "稀矿厂", "每小时产出稀矿",
                Map.of("steel", 120, "oil", 40), 1.6, "res", GameConstants.GROUP_SLOTS_RES_MAX,
                null, "rare", 500, null, null, null, null));
        m.put("depot", new BuildingDef("depot", "军需仓库", "每级为粮食、钢铁、石油、稀矿各提供1000掠夺保护额度，可叠加仓储技术；不增加生产或库存上限",
                Map.of("steel", 100), 1.5, "res", GameConstants.GROUP_SLOTS_ARMY_MAX,
                null, null, null, null, 1000, null, null));
        m.put("lab", new BuildingDef("lab", "军工科技研发中心", "升级解锁更多科技，并提高可研究等级上限；不加速科研",
                Map.of("steel", 200, "food", 100, "rare", 20), 1.6, "core", 1));
        m.put("radar", new BuildingDef("radar", "城防雷达", "地图扫描半径每级增加1格，支撑侦察与雷达预警科研；本城基本来袭提醒无需此建筑，建筑不直接增加战斗属性或联防覆盖",
                Map.of("steel", 180, "oil", 60, "rare", 20), 1.6, "core", 1));
        m.put("guild_radar", new BuildingDef("guild_radar", "联防雷达", "用于军团区域联防，每级覆盖半径增加1格；开启后发现路线经过覆盖区且威胁团友的敌对行军，不影响本城受袭提醒",
                Map.of("steel", 180, "oil", 60, "rare", 20), 1.6, "core", 1));
        m.put("wall", new BuildingDef("wall", "城防", "征服战须先清除守军再破墙；每级墙体生命+250000、防御+20，四类工事共享容量+3000",
                Map.of("steel", 200, "food", 80), 1.5, "def", 1));
        m.put("apron", new BuildingDef("apron", "战备机场", "本城出发空军世界行军速度+3%/级，与喷气推进相加；不提高战场移动速度",
                Map.of("steel", 220, "oil", 80, "rare", 20), 1.6, "def", 1,
                null, null, null, null, null, 3, null));
        m.put("transit", new BuildingDef("transit", "战地兵站", "本城粮食、钢铁、石油、稀矿自然产量+3%/级；不影响黄金税收或野地采集",
                Map.of("steel", 160, "food", 80), 1.6, "res", 1,
                null, null, null, null, null, null, 3));
        m.put("hospital", new BuildingDef("hospital", "战地医院", "支撑医疗技术研发并提供伤兵营入口；医院等级不直接提高伤兵比例、治疗速度或降低费用",
                Map.of("steel", 240, "food", 160, "rare", 20), 1.6, "army", 1));
        m.put("liaison", new BuildingDef("liaison", "联络中心", "联防雷达建设及雷达预警等科研的配套前置；不直接解锁外交权限或增加情报明细",
                Map.of("steel", 200, "food", 120, "gold", 200), 1.6, "core", 1));
        m.put("exchange", new BuildingDef("exchange", "军需物资中转站", "转换四种基础资源并进行市场交易；每级提高兑换比例与挂单数，降低交易税率",
                Map.of("steel", 180, "food", 100, "gold", 100), 1.5, "res", 1));
        m.put("prisoner_camp", new BuildingDef("prisoner_camp", "战俘营", "收容战俘，每级容量2000；黄金犒赏后可招募归队",
                Map.of("steel", 400, "food", 200, "gold", 150), 1.6, "army", 1));
        BUILDINGS = Collections.unmodifiableMap(m);
    }
}
