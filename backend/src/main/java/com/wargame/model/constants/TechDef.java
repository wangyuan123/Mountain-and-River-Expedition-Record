package com.wargame.model.constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 科技定义 - 对应 data.js 中 G.DATA.techs
 */
public record TechDef(
        String key,
        String name,
        String branch,
        String desc,
        int maxLevel,
        int labReq,
        Map<String, Integer> cost,
        double costGrowth,
        String effect
) {
    public static final Map<String, TechDef> TECHS;
    static {
        Map<String, TechDef> m = new HashMap<>();
        // 指挥
        m.put("attack_tech", new TechDef("attack_tech", "攻击科技", "军事", "全军攻击 +10%/级",
                10, 1, Map.of("steel", 240, "food", 120), 1.7, "atk_all"));
        m.put("defense_tech", new TechDef("defense_tech", "防御科技", "军事", "全军防御 +10%/级",
                10, 2, Map.of("steel", 240, "food", 120), 1.7, "def_all"));
        m.put("weapon_range", new TechDef("weapon_range", "武器射程", "军事", "全军武器射程 +10%/级", 10, 2, Map.of("steel", 280, "food", 140, "rare", 20), 1.8, "range_all"));
        m.put("cmd_hp", new TechDef("cmd_hp", "军队生命", "军事", "军队生命 +10%/级",
                10, 3, Map.of("steel", 300, "food", 160, "rare", 30), 1.8, "hp_all"));
        // 步兵
        m.put("inf_load", new TechDef("inf_load", "步兵负重", "步兵", "步兵负重 +20%/级(掠夺)",
                5, 2, Map.of("steel", 200, "food", 100), 1.6, "load"));
        // 装甲
        m.put("arm_engine", new TechDef("arm_engine", "燃烧引擎", "装甲", "装甲系移动 +5%/级",
                10, 3, Map.of("steel", 320, "oil", 120, "rare", 40), 1.8, "spd_arm"));
        // 航空
        m.put("air_engine", new TechDef("air_engine", "喷气推进", "航空", "空军移动 +5%/级",
                10, 4, Map.of("steel", 360, "oil", 160, "rare", 70), 1.9, "spd_air"));
        // 航海
        m.put("nav_engine", new TechDef("nav_engine", "舰船动力", "航海", "海军移动 +5%/级",
                10, 5, Map.of("steel", 400, "oil", 200, "rare", 100), 2.0, "spd_nav"));
        // 后勤
        m.put("log_production", new TechDef("log_production", "资源采集", "后勤", "资源产出 +5%/级",
                10, 1, Map.of("steel", 320, "food", 160), 1.8, "res"));
        m.put("log_warehouse", new TechDef("log_warehouse", "仓储技术", "后勤", "资源上限 +10%/级",
                5, 2, Map.of("steel", 280, "food", 140), 1.7, "cap"));
        m.put("log_food", new TechDef("log_food", "军需补给", "后勤", "养兵耗粮 -5%/级",
                10, 3, Map.of("steel", 360, "food", 200), 1.8, "food_save"));
        m.put("log_train", new TechDef("log_train", "训练加速", "后勤", "征召批量 +10%/级",
                10, 2, Map.of("steel", 300, "food", 180, "gold", 100), 1.8, "train"));
        m.put("log_build", new TechDef("log_build", "建筑加速", "后勤", "建筑升级资源 -5%/级",
                10, 2, Map.of("steel", 340, "food", 160, "gold", 120), 1.8, "build"));
        m.put("log_medical", new TechDef("log_medical", "医疗技术", "后勤", "伤兵可回收 +5%/级，最高50%",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "medical"));
        // 侦察
        m.put("recon_level", new TechDef("recon_level", "侦察技术", "侦察", "侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领",
                5, 1, Map.of("steel", 180, "oil", 60), 1.6, "recon"));
        m.put("recon_radar", new TechDef("recon_radar", "雷达预警", "侦察", "提前发现敌方 +1 回合",
                3, 2, Map.of("steel", 240, "oil", 100, "rare", 20), 1.7, "radar"));
        TECHS = Collections.unmodifiableMap(m);
    }
}
