package com.wargame.model.constants;

import java.util.List;
import java.util.Map;

/**
 * 游戏数据聚合类 - 对应 JS 中 G.DATA 的便捷访问入口。
 * 使用方式与 JS 的 G.DATA.xxx 一致，例如 GameData.RESOURCES、GameData.BUILDINGS 等。
 */
public final class GameData {

    private GameData() {}

    public static final String VERSION = GameConstants.VERSION;
    public static final String SAVE_KEY = GameConstants.SAVE_KEY;

    // ===== 编组槽位 =====
    public static final int GROUP_SLOTS_RES = GameConstants.GROUP_SLOTS_RES;
    public static final int GROUP_SLOTS_ARMY = GameConstants.GROUP_SLOTS_ARMY;

    // ===== 军官名字与军衔 =====
    public static final List<String> OFFICER_NAMES = GameConstants.OFFICER_NAMES;
    public static final List<String> OFFICER_TITLES = GameConstants.OFFICER_TITLES;

    // ===== 资源 =====
    public static final Map<String, ResourceDef> RESOURCES = ResourceDef.RESOURCES;

    // ===== 建筑 =====
    public static final Map<String, BuildingDef> BUILDINGS = BuildingDef.BUILDINGS;

    // ===== 单位 =====
    public static final Map<String, UnitDef> UNITS = UnitDef.UNITS;

    // ===== 科技 =====
    public static final Map<String, TechDef> TECHS = TechDef.TECHS;

    // ===== 城防设施 =====
    public static final Map<String, FortDef> FORTS = FortDef.FORTS;

    // ===== 野地类型 =====
    public static final Map<String, WildTypeDef> WILD_TYPES = WildTypeDef.WILD_TYPES;

    // ===== 道具 =====
    public static final Map<String, ItemDef> ITEMS = ItemDef.ITEMS;

    // ===== 军官技能 =====
    public static final Map<String, OfficerSkillDef> OFFICER_SKILLS = OfficerSkillDef.OFFICER_SKILLS;

    // ===== 阵营 =====
    public static final Map<String, FactionDef> FACTIONS = FactionDef.FACTIONS;

    // ===== 星级颜色 =====
    public static final Map<Integer, String> STAR_COLOR = StarColor.STAR_COLOR;

    // ===== 历史名将 =====
    public static final List<HistoricalOfficers.Officer> HISTORICAL_OFFICERS = HistoricalOfficers.HISTORICAL_OFFICERS;

    // ===== 世界配置 =====
    public static final int WORLD_SIZE = WorldConfig.SIZE;
    public static final int VIEW_RADIUS = WorldConfig.VIEW_RADIUS;
    public static final int MARCH_SEC_PER_GRID = WorldConfig.MARCH_SEC_PER_GRID;
    public static final List<String> BANDIT_NAMES = WorldConfig.BANDIT_NAMES;
    public static final List<WorldConfig.BanditLevel> BANDIT_LEVELS = WorldConfig.BANDIT_LEVELS;
    public static final List<String> NPC_CITY_NAMES = WorldConfig.NPC_CITY_NAMES;
}
