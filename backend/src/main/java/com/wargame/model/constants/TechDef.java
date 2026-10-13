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
        m.put("inf_load", new TechDef("inf_load", "步兵负重", "后勤", "当前版本暂无实际负重加成。部队可携带资源量按兵种基础负重和已配装装备计算。",
                5, 2, Map.of("steel", 200, "food", 100), 1.6, "load"));
        // 装甲
        m.put("arm_engine", new TechDef("arm_engine", "燃烧引擎", "机动", "装甲与卡车移动 +5%/级",
                10, 3, Map.of("steel", 320, "oil", 120, "rare", 40), 1.8, "spd_arm"));
        // 航空
        m.put("air_engine", new TechDef("air_engine", "喷气推进", "机动", "空军移动 +5%/级",
                10, 4, Map.of("steel", 360, "oil", 160, "rare", 70), 1.9, "spd_air"));
        // 航海
        m.put("nav_engine", new TechDef("nav_engine", "舰船动力", "机动", "海军移动 +5%/级",
                10, 5, Map.of("steel", 400, "oil", 200, "rare", 100), 2.0, "spd_nav"));
        // 后勤
        m.put("log_production", new TechDef("log_production", "资源采集", "后勤", "提高城内粮食、钢铁、石油、稀矿建筑产量，每级增加 5% 基础产量。",
                10, 1, Map.of("steel", 320, "food", 160), 1.8, "res"));
        m.put("log_warehouse", new TechDef("log_warehouse", "仓储技术", "后勤", "当前版本暂无实际容量加成。粮食、钢铁、石油、稀矿的储量上限由本城对应资源建筑的等级总和决定，每级 20 万。",
                5, 2, Map.of("steel", 280, "food", 140), 1.7, "cap"));
        m.put("log_food", new TechDef("log_food", "军需补给", "后勤", "养兵耗粮减少 5%/级，最高减少 50%。",
                10, 3, Map.of("steel", 360, "food", 200), 1.8, "food_save"));
        m.put("log_train", new TechDef("log_train", "训练加速", "后勤", "兵种生产速度 +10%/级，与生产设施的速度加成相加。征召数量仍受平民人口与资源限制。",
                10, 2, Map.of("steel", 300, "food", 180, "gold", 100), 1.8, "train"));
        m.put("log_build", new TechDef("log_build", "建筑加速", "后勤", "建筑新建和升级的资源消耗、基础工期均减少 5%/级，满级各减少 50%。",
                10, 2, Map.of("steel", 340, "food", 160, "gold", 120), 1.8, "build"));
        m.put("log_medical", new TechDef("log_medical", "医疗技术", "后勤", "战损部队可回收为伤兵的比例每级增加 5 个百分点，本科技最高提供 50%，可叠加参战军官的急救技能。伤兵需在 7 天内消耗黄金或钻石治疗后归队。",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "medical"));
        // 侦察
        m.put("recon_level", new TechDef("recon_level", "侦察技术", "侦察", "侦查成功后，基础资源始终可见，等级越高能查看的情报越详细。城市逐级解锁：Lv.1 城防；Lv.2 精确守军与统帅；Lv.3 建筑与将领人数；Lv.4 科技与可掠夺资源测算；Lv.5 将领档案与战力。野地 Lv.1 起可查看精确守军。",
                5, 1, Map.of("steel", 180, "oil", 60), 1.6, "recon"));
        m.put("recon_radar", new TechDef("recon_radar", "雷达预警", "侦察", "拦截敌方侦查时，守方侦查部队每级增加 2 点先手值，先手值越高，出手越早。需驻防侦察机拦截空中侦查，或侦查装甲车拦截地面侦查。",
                3, 2, Map.of("steel", 240, "oil", 100, "rare", 20), 1.7, "radar"));
        m.put("prisoner_education", new TechDef("prisoner_education", "战俘思想教育", "后勤", "讨伐其他玩家并获胜后，按敌方各兵种损失数量计算战俘。俘获比例每级增加 0.1 个百分点，最高 1%，不足 1 个向下取整。需战俘营有空位，敌方已回收的伤兵不能被俘获。",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "capture_permille"));
        m.put("unit_refit", new TechDef("unit_refit", "兵种装备工程", "军事", "Lv.1/2/3 分别解锁同等级兵种装备配方，制造需本城对应生产设施 Lv.2/4/6。装备制造并配装后才获得装备效果。",
                3, 2, Map.of("steel", 300, "oil", 100, "rare", 50), 2.0, "unit_recipes"));
        TECHS = Collections.unmodifiableMap(m);
    }
}
