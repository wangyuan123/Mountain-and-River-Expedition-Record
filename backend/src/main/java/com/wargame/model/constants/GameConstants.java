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

    /** 已完成建筑的统一等级上限，升级校验和主线目标共用。 */
    public static final int BUILDING_MAX_LEVEL = 10;

    /** 世界频道首次解锁声望门槛；解锁结果由玩家账号永久保存。 */
    public static final int WORLD_CHAT_PRESTIGE_THRESHOLD = 10000;

    /** 新建 PvP 编队和入队的声望门槛，状态展示与执行共用。 */
    public static final int PVP_PRESTIGE_THRESHOLD = 10000;

    /** 资源区与军工区各固定35个共享卡槽，不随市政厅等级扩容；与前端 G.DATA 保持一致。 */
    public static final int GROUP_SLOTS_RES = 35;
    public static final int GROUP_SLOTS_RES_MAX = GROUP_SLOTS_RES;
    public static final int GROUP_SLOTS_ARMY = 35;
    public static final int GROUP_SLOTS_ARMY_MAX = GROUP_SLOTS_ARMY;

    /** 军官名字池 - 对应 G.DATA.officerNames */
    public static final List<String> OFFICER_NAMES = List.of(
            // 中国远征军及抗日名将
            "孙立人", "戴安澜", "卫立煌", "杜聿明", "廖耀湘", "郑洞国", "宋希濂", "霍揆彰", "齐学启", "李宗仁", "薛岳", "张自忠", "梁思成",
            // 中缅印战区 (CBI) 盟军统帅
            "史迪威", "陈纳德", "斯利姆",
            // 二战盟军将领
            "麦克阿瑟", "尼米兹", "艾森豪威尔", "巴顿", "蒙哥马利", "布雷德利", "朱可夫", "华西列夫斯基", "崔可夫", "切尔尼亚霍夫斯基", "施瓦茨科普夫",
            // 二战欧洲其他名将
            "隆美尔", "古德里安", "曼施坦因", "邓尼茨", "莫德尔", "龙德施泰特", "海因里希", "克卢格", "霍特"
    );

    /** 军官军衔 - 对应 G.DATA.officerTitles */
    public static final List<String> OFFICER_TITLES = List.of(
            "列兵", "上士", "少尉", "中尉", "上尉", "少校", "中校", "上校", "准将", "少将", "中将", "上将"
    );

    /** 军衔名称与声望参考门槛统一取自晋升定义，不代表账号已获授衔。 */
    public static final List<String> MILITARY_RANKS = MilitaryRankDef.RANKS.stream().map(MilitaryRankDef.RankInfo::name).toList();
    public static final int[] PRESTIGE_LEVEL_THRESHOLDS = MilitaryRankDef.RANKS.stream().mapToInt(MilitaryRankDef.RankInfo::prestige).toArray();

    /** 根据声望返回可达门槛的参考名称；实际军衔读取玩家已授衔阶数，不能据此自动晋升。 */
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
}
