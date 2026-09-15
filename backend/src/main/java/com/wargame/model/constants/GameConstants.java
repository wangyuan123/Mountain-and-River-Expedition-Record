package com.wargame.model.constants;

import java.util.List;
import java.util.Map;

/**
 * 游戏主常量入口 - 对应 data.js 中 G.DATA 的顶层字段
 */
public final class GameConstants {

    private GameConstants() {}

    public static final String VERSION = "2.0.0";
    public static final String SAVE_KEY = "ww2_wistone_v1";

    /** 编组槽位 - 对应 G.DATA.groupSlots */
    public static final int GROUP_SLOTS_RES = 12;
    public static final int GROUP_SLOTS_RES_MAX = 32;
    public static final int GROUP_SLOTS_ARMY = 12;
    public static final int GROUP_SLOTS_ARMY_MAX = 32;

    /** 军官名字池 - 对应 G.DATA.officerNames */
    public static final List<String> OFFICER_NAMES = List.of(
            "隆美尔", "朱可夫", "巴顿", "蒙哥马利", "古德里安", "曼施坦因", "麦克阿瑟", "尼米兹",
            "山本五十六", "邓尼茨", "崔可夫", "艾森豪威尔", "布雷德利", "莫德尔", "龙德施泰特", "华西列夫斯基",
            "海因里希", "克卢格", "霍特", "切尔尼亚霍夫斯基", "梁思成", "施瓦茨科普夫", "李宗仁", "孙立人"
    );

    /** 军官军衔 - 对应 G.DATA.officerTitles */
    public static final List<String> OFFICER_TITLES = List.of(
            "列兵", "上士", "少尉", "中尉", "上尉", "少校", "中校", "上校", "准将", "少将", "中将", "上将"
    );

    /** 统帅军衔体系（基于声望晋升，玩家不设等级划分） */
    public static final List<String> MILITARY_RANKS = List.of(
            "列兵", "上等兵", "下士", "中士", "上士", "军士长",
            "准尉", "少尉", "中尉", "上尉", "少校", "中校",
            "上校", "大校", "少将", "中将", "上将", "元帅"
    );

    /** 统帅军衔对应的声望门槛 */
    public static final int[] PRESTIGE_LEVEL_THRESHOLDS = {
            0,        // 列兵
            200,      // 上等兵
            500,      // 下士
            1000,     // 中士
            2000,     // 上士
            3500,     // 军士长
            5500,     // 准尉
            8000,     // 少尉
            15000,    // 中尉
            25000,    // 上尉
            45000,    // 少校
            80000,    // 中校
            150000,   // 上校
            300000,   // 大校
            600000,   // 少将
            1200000,  // 中将
            2500000,  // 上将
            5000000   // 元帅 (满级军衔)
    };

    /** 根据声望获取军衔名称（玩家不设等级） */
    public static String getRankTitleByPrestige(int prestige) {
        if (prestige <= 0) return MILITARY_RANKS.get(0);
        for (int i = PRESTIGE_LEVEL_THRESHOLDS.length - 1; i >= 0; i--) {
            if (prestige >= PRESTIGE_LEVEL_THRESHOLDS[i]) {
                return MILITARY_RANKS.get(i);
            }
        }
        return MILITARY_RANKS.get(0);
    }

    // ===== 子常量引用 =====

    public static final Map<String, ResourceDef> RESOURCES = ResourceDef.RESOURCES;
    public static final Map<String, BuildingDef> BUILDINGS = BuildingDef.BUILDINGS;
    public static final Map<String, UnitDef> UNITS = UnitDef.UNITS;
    public static final Map<String, TechDef> TECHS = TechDef.TECHS;
    public static final Map<String, FortDef> FORTS = FortDef.FORTS;
    public static final Map<String, WildTypeDef> WILD_TYPES = WildTypeDef.WILD_TYPES;
    public static final Map<String, OfficerSkillDef> OFFICER_SKILLS = OfficerSkillDef.OFFICER_SKILLS;
    public static final Map<String, FactionDef> FACTIONS = FactionDef.FACTIONS;
    public static final Map<Integer, String> STAR_COLOR = StarColor.STAR_COLOR;
    public static final List<HistoricalOfficers.Officer> HISTORICAL_OFFICERS = HistoricalOfficers.HISTORICAL_OFFICERS;

    // ===== 世界配置引用 =====

    public static final int WORLD_SIZE = WorldConfig.SIZE;
    public static final int VIEW_RADIUS = WorldConfig.VIEW_RADIUS;
    public static final int MARCH_SEC_PER_GRID = WorldConfig.MARCH_SEC_PER_GRID;
    public static final List<String> BANDIT_NAMES = WorldConfig.BANDIT_NAMES;
    public static final List<WorldConfig.BanditLevel> BANDIT_LEVELS = WorldConfig.BANDIT_LEVELS;
    public static final List<String> NPC_CITY_NAMES = WorldConfig.NPC_CITY_NAMES;
}
