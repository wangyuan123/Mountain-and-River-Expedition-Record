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
        m.put("cmd_attack", new TechDef("cmd_attack", "攻击指挥", "指挥", "全军攻击 +5%/级",
                10, 1, Map.of("steel", 240, "food", 120), 1.7, "atk_all"));
        m.put("cmd_defense", new TechDef("cmd_defense", "防御指挥", "指挥", "全军防御 +5%/级",
                10, 2, Map.of("steel", 240, "food", 120), 1.7, "def_all"));
        m.put("cmd_hp", new TechDef("cmd_hp", "集结战术", "指挥", "全军生命 +5%/级",
                10, 3, Map.of("steel", 300, "food", 160, "rare", 30), 1.8, "hp_all"));
        // 步兵
        m.put("inf_attack", new TechDef("inf_attack", "步兵攻击", "步兵", "步兵系攻击 +5%/级",
                10, 1, Map.of("steel", 180, "food", 90), 1.7, "atk_inf"));
        m.put("inf_defense", new TechDef("inf_defense", "步兵防御", "步兵", "步兵系防御 +5%/级",
                10, 1, Map.of("steel", 180, "food", 90), 1.7, "def_inf"));
        m.put("inf_load", new TechDef("inf_load", "步兵负重", "步兵", "步兵负重 +20%/级(掠夺)",
                5, 2, Map.of("steel", 200, "food", 100), 1.6, "load"));
        // 装甲
        m.put("arm_attack", new TechDef("arm_attack", "装甲攻击", "装甲", "装甲系攻击 +5%/级",
                10, 2, Map.of("steel", 260, "food", 130, "rare", 20), 1.8, "atk_arm"));
        m.put("arm_defense", new TechDef("arm_defense", "装甲防御", "装甲", "装甲系防御 +5%/级",
                10, 2, Map.of("steel", 260, "food", 130, "rare", 20), 1.8, "def_arm"));
        m.put("arm_engine", new TechDef("arm_engine", "燃烧引擎", "装甲", "装甲系移动 +5%/级",
                10, 3, Map.of("steel", 320, "oil", 120, "rare", 40), 1.8, "spd_arm"));
        // 航空
        m.put("air_attack", new TechDef("air_attack", "航空攻击", "航空", "空军攻击 +5%/级",
                10, 3, Map.of("steel", 300, "food", 150, "rare", 50), 1.8, "atk_air"));
        m.put("air_defense", new TechDef("air_defense", "航空防御", "航空", "空军防御 +5%/级",
                10, 3, Map.of("steel", 300, "food", 150, "rare", 50), 1.8, "def_air"));
        m.put("air_engine", new TechDef("air_engine", "喷气推进", "航空", "空军移动 +5%/级",
                10, 4, Map.of("steel", 360, "oil", 160, "rare", 70), 1.9, "spd_air"));
        // 航海
        m.put("nav_attack", new TechDef("nav_attack", "航海攻击", "航海", "海军攻击 +5%/级",
                10, 4, Map.of("steel", 340, "food", 180, "rare", 70), 1.9, "atk_nav"));
        m.put("nav_defense", new TechDef("nav_defense", "航海防御", "航海", "海军防御 +5%/级",
                10, 4, Map.of("steel", 340, "food", 180, "rare", 70), 1.9, "def_nav"));
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
        m.put("log_medical", new TechDef("log_medical", "医疗技术", "后勤", "败战部队存活率 +5%/级",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "medical"));
        // 侦察
        m.put("recon_level", new TechDef("recon_level", "侦察技术", "侦察", "侦察野地等级 +1/级",
                5, 1, Map.of("steel", 180, "oil", 60), 1.6, "recon"));
        m.put("recon_radar", new TechDef("recon_radar", "雷达预警", "侦察", "提前发现敌方 +1 回合",
                3, 2, Map.of("steel", 240, "oil", 100, "rare", 20), 1.7, "radar"));
        m.put("recon_stealth", new TechDef("recon_stealth", "反侦察", "侦察", "降低被侦察成功率",
                5, 3, Map.of("steel", 220, "oil", 80, "rare", 30), 1.7, "stealth"));
        TECHS = Collections.unmodifiableMap(m);
    }
}
