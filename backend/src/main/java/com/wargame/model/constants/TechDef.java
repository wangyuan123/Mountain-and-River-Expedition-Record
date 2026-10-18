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
        m.put("attack_tech", new TechDef("attack_tech", "攻击科技", "军事", "全军攻击 +5%/级，最高 +50%",
                10, 1, Map.of("steel", 240, "food", 120), 1.7, "atk_all"));
        m.put("defense_tech", new TechDef("defense_tech", "防御科技", "军事", "全军防御 +5%/级，最高 +50%",
                10, 2, Map.of("steel", 240, "food", 120), 1.7, "def_all"));
        m.put("weapon_range", new TechDef("weapon_range", "武器射程", "军事", "全军武器射程 +5%/级，最高 +50%", 10, 2, Map.of("steel", 280, "food", 140, "rare", 20), 1.8, "range_all"));
        m.put("cmd_hp", new TechDef("cmd_hp", "军队生命", "军事", "军队生命 +5%/级，最高 +50%",
                10, 3, Map.of("steel", 300, "food", 160, "rare", 30), 1.8, "hp_all"));
        // 对应军队生命的独立输出成长：增加行动火力，不增加出手次数，也不并入武器攻击值。
        m.put("fire_density", new TechDef("fire_density", "火力密度", "军事", "全军行动火力 +5%/级，最高 +50%；与攻击科技独立相乘，主动攻击、余伤攻击和反击均生效。",
                10, 3, Map.of("steel", 300, "food", 160, "rare", 30), 1.8, "firepower_all"));
        // 城墙科技只强化真实玩家墙体，不能叠加军队攻防生命乘区。
        m.put("wall_reinforcement", new TechDef("wall_reinforcement", "城墙加固", "军事", "当前城墙生命与防御 +5%/级，最高各 +50%；无城墙时不产生属性，不强化驻军或城防工事。",
                10, 2, Map.of("steel", 320, "food", 160, "rare", 30), 1.8, "wall_stats"));
        m.put("log_load", new TechDef("log_load", "负重科技", "后勤", "所有兵种负重 +5%/级，最高 +50%；与装备负重相乘，运输机运力同样生效。",
                10, 2, Map.of("steel", 200, "food", 100), 1.6, "load"));
        // 装甲
        m.put("arm_engine", new TechDef("arm_engine", "燃烧引擎", "机动", "陆军移动 +5%/级",
                10, 3, Map.of("steel", 320, "oil", 120, "rare", 40), 1.8, "spd_arm"));
        // 航空
        m.put("air_engine", new TechDef("air_engine", "喷气推进", "机动", "空军移动 +5%/级",
                10, 4, Map.of("steel", 360, "oil", 160, "rare", 70), 1.9, "spd_air"));
        // 航海
        m.put("nav_engine", new TechDef("nav_engine", "舰船动力", "机动", "海军移动 +5%/级",
                10, 5, Map.of("steel", 400, "oil", 200, "rare", 100), 2.0, "spd_nav"));
        // 后勤
        m.put("prod_food", new TechDef("prod_food", "粮食产量", "后勤", "城内粮食产量 +5%/级，最高 +50%，不影响野地采集。",
                10, 1, Map.of("steel", 80, "food", 40), 1.8, "res_food"));
        m.put("prod_steel", new TechDef("prod_steel", "钢铁产量", "后勤", "城内钢铁产量 +5%/级，最高 +50%，不影响野地采集。",
                10, 1, Map.of("steel", 80, "food", 40), 1.8, "res_steel"));
        m.put("prod_oil", new TechDef("prod_oil", "石油产量", "后勤", "城内石油产量 +5%/级，最高 +50%，不影响野地采集。",
                10, 1, Map.of("steel", 80, "food", 40), 1.8, "res_oil"));
        m.put("prod_rare", new TechDef("prod_rare", "稀矿产量", "后勤", "城内稀矿产量 +5%/级，最高 +50%，不影响野地采集。",
                10, 1, Map.of("steel", 80, "food", 40), 1.8, "res_rare"));
        m.put("log_warehouse", new TechDef("log_warehouse", "仓储技术", "后勤", "本城军需仓库掠夺保护额度 +10%/级，最高 +50%；不增加资源储量上限。",
                5, 2, Map.of("steel", 280, "food", 140), 1.7, "cap"));
        m.put("log_food", new TechDef("log_food", "军需补给", "后勤", "养兵耗粮减少 5%/级，最高减少 50%。",
                10, 3, Map.of("steel", 360, "food", 200), 1.8, "food_save"));
        m.put("log_train", new TechDef("log_train", "训练加速", "后勤", "兵种生产速度 +5%/级，最高 +50%，与生产设施的速度加成相加。征召数量仍受平民人口与资源限制。",
                10, 2, Map.of("steel", 300, "food", 180, "gold", 100), 1.8, "train"));
        m.put("log_build", new TechDef("log_build", "建筑加速", "后勤", "建筑新建和升级的资源消耗、基础工期均减少 5%/级，满级各减少 50%。",
                10, 2, Map.of("steel", 340, "food", 160, "gold", 120), 1.8, "build"));
        m.put("log_medical", new TechDef("log_medical", "医疗技术", "后勤", "战损部队可回收为伤兵的比例每级增加 5 个百分点，本科技最高提供 50%，可叠加参战军官的急救技能。伤兵需在 7 天内消耗黄金或钻石治疗后归队。",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "medical"));
        // 侦察
        m.put("recon_level", new TechDef("recon_level", "侦察技术", "侦察", "侦查交火时，攻守双方各按自身等级每级增加5点先手值，最高25点，不增加地图扫描半径。侦查成功后，基础资源始终可见，等级越高能查看的情报越详细。城市逐级解锁：Lv.1 城防；Lv.2 精确守军与统帅；Lv.3 建筑与将领人数；Lv.4 科技与可掠夺资源测算；Lv.5 将领档案与战力。野地 Lv.1 起可查看精确守军。",
                5, 1, Map.of("steel", 180, "oil", 60), 1.6, "recon"));
        m.put("recon_radar", new TechDef("recon_radar", "雷达预警", "侦察", "拦截敌方侦查的首回合，守方侦查部队每级增加5点先手值，最高25点，后续回合不再提供预警加值。Lv.1—5逐级开放来袭来源、兵力区间、兵种与总兵力、精确编成与将领、战斗科技与装备情报。需驻防侦察机拦截空中侦查，或侦查装甲车拦截地面侦查。",
                5, 2, Map.of("steel", 240, "oil", 100, "rare", 20), 1.7, "radar"));
        m.put("prisoner_education", new TechDef("prisoner_education", "战俘收编", "后勤", "讨伐其他玩家并获胜后，按敌方各兵种损失数量计算战俘。俘获比例每级增加 0.1 个百分点，最高 1%，不足 1 个向下取整。需战俘营有空位，敌方已回收的伤兵不能被俘获。",
                10, 3, Map.of("steel", 320, "food", 220, "gold", 150), 1.8, "capture_permille"));
        m.put("unit_refit", new TechDef("unit_refit", "武器装备", "军事", "Lv.1—5依次解锁基础防护、常规武器与载荷、通信与机动、专用防护、高级火控与反制配方，制造需本城对应生产设施 Lv.2/4/6/8/10。装备制造并配装后才获得装备效果。",
                5, 2, Map.of("steel", 300, "oil", 100, "rare", 50), 2.0, "unit_recipes"));
        TECHS = Collections.unmodifiableMap(m);
    }
}
