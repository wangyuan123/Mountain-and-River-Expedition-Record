package com.wargame.model.constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界配置 - 对应 data.js 中 G.DATA.world
 */
public final class WorldConfig {

    private WorldConfig() {}

    /** 地形存档及单战场为 400×400；四大战场共用 800×800 全局坐标。 */
    public static final int SIZE = 400;
    public static final int WORLD_SIZE = SIZE * 2;
    public static final int VIEW_RADIUS = 3;
    public static final int MARCH_SEC_PER_GRID = 18;
    /** 单队行军令只加速当前路段，最多一次且保留至少三十秒的抵达窗口。 */
    public static final long MARCH_ACCELERATION_MIN_MS = StratagemRules.MIN_MARCH_REMAINING_MS;
    public static final int MAX_NPC_LEVEL = 30;
    public static final int MAX_WILD_LEVEL = 30;
    public static final int RES_PER_WILD_LEVEL = 160_000;
    /** 陆地据点沿用四类资源野地合计配额：120／100／80 个每级，共 3,300 个。 */
    public static int landBanditQuota(int level) {
        if (level < 1 || level > MAX_NPC_LEVEL) throw new IllegalArgumentException("陆地据点等级必须为 1～30");
        return level <= 20 ? 120 : level <= 25 ? 100 : 80;
    }

    public static final int MIN_LAND_WILDS_PER_WORLD = 120;
    public static final int MIN_SEA_WILDS_PER_WORLD = 40;

    /**
     * Resource-wild garrison rule. Land tiles use land/air combat units only;
     * sea tiles use naval units only. The level multiplier is (level + 9),
     * with the requested additional fourfold sea scaling.
     */
    public static Map<String, Integer> wildGarrison(int level, boolean sea) {
        int multiplier = level + 9;
        Map<String, Integer> base = new LinkedHashMap<>();
        if (sea) {
            if (level <= 10) {
                base.put("sub", level * 2);
                if (level >= 4) base.put("destroyer", Math.max(1, (level - 2) / 2));
            } else if (level <= 20) {
                base.put("sub", level);
                base.put("destroyer", level - 6);
                if (level >= 12) base.put("battleship", (level - 10) / 2);
                if (level >= 17) base.put("carrier", 1);
            } else {
                base.put("sub", level + (level - 20) * 2);
                base.put("destroyer", level + (level - 20));
                base.put("battleship", 5 + (level - 20) * 2);
                base.put("carrier", 1 + (level - 20) / 2);
            }
            base.replaceAll((unit, count) -> count * multiplier * 4);
            return base;
        }
        base.put("infantry", level <= 3 ? level * 5 : level <= 10 ? 18 + (level - 4) * 4 + (level - 3) * 2 : 62 + (level - 11) * 8);
        if (level >= 8) base.put("armored", level <= 10 ? level - 7 : 4 + (level - 11) * 2);
        if (level >= 11) {
            base.put("ltank", Math.max(1, level - 10));
            if (level >= 13) base.put("special", level - 11);
            if (level >= 15) base.put("assault", level - 14);
            if (level >= 17) base.put("htank", level - 16);
        }
        if (level >= 21) {
            base.put("rocket", 2 + (level - 21) / 2);
            base.put("fighter", Math.max(1, (level - 20) / 2));
            if (level >= 23) base.put("bomber", (level - 21) / 2);
        }
        base.replaceAll((unit, count) -> count * multiplier);
        return base;
    }

    /** 海洋 NPC 默认候选编队；地图目标通过坐标入口选择同级多编队。 */
    public static Map<String, Integer> seaNpcGarrison(int level) {
        return SeaNpcGarrison.army(level, 0);
    }

    /** 按海洋目标坐标选择并返回稳定的同级编队；只用于新建和战败重生。 */
    public static Map<String, Integer> seaNpcGarrison(int level, int x, int y) {
        return SeaNpcGarrison.army(level, x * 31L + y);
    }

    public static final List<String> BANDIT_NAMES = List.of(
            "寇"
    );

    /** 陆地寇仅使用一个名称，等级后缀供行军与战报识别难度。 */
    public static String landBanditName(Integer level) {
        return "寇 Lv." + Math.max(1, level == null ? 1 : level);
    }

    public static final List<BanditLevel> BANDIT_LEVELS = createBanditLevels();

    /** 生成各级默认编队与既有奖励；地图实际守军通过生成位置选取同级的不同编队。 */
    private static List<BanditLevel> createBanditLevels() {
        List<Map<String, Integer>> earlyRewards = List.of(
                Map.of("food", 80, "steel", 120, "oil", 60, "rare", 10, "gold", 15),
                Map.of("food", 120, "steel", 180, "oil", 90, "rare", 15, "gold", 20),
                Map.of("food", 180, "steel", 260, "oil", 140, "rare", 25, "gold", 30),
                Map.of("food", 240, "steel", 360, "oil", 200, "rare", 40, "gold", 45),
                Map.of("food", 320, "steel", 480, "oil", 280, "rare", 60, "gold", 70),
                Map.of("food", 440, "steel", 660, "oil", 400, "rare", 90, "gold", 100),
                Map.of("food", 600, "steel", 900, "oil", 560, "rare", 130, "gold", 150),
                Map.of("food", 800, "steel", 1200, "oil", 760, "rare", 180, "gold", 220));
        List<BanditLevel> levels = new ArrayList<>();
        for (int level = 1; level <= MAX_NPC_LEVEL; level++) {
            Map<String, Integer> base = level <= 8 ? earlyRewards.get(level - 1)
                    : Map.of("food", level * 160, "steel", level * 240, "oil", level * 140,
                            "rare", level * 45, "gold", level * 60);
            Map<String, Integer> reward = new LinkedHashMap<>();
            // 奖励独立于新守军曲线，继续沿用原资源倍率及胜利时的概率钻石结算。
            base.forEach((resource, amount) -> reward.put(resource,
                    amount * ("rare".equals(resource) ? 45 : "gold".equals(resource) ? 15 : 30)));
            levels.add(new BanditLevel(level, LandNpcGarrison.army(level, 0), Map.copyOf(reward)));
        }
        return List.copyOf(levels);
    }

    /** 同等级按坐标分配编队；仅新建或战败迁移刷新时调用，读取存档时不重配残兵。 */
    public static Map<String, Integer> landNpcGarrison(int level, int x, int y) {
        return LandNpcGarrison.army(level, x * 31L + y);
    }

    /** 按等级返回陆海 NPC 共用的五类资源奖励；钻石只在胜利结算时单独抽取。 */
    public static Map<String, Integer> npcReward(int level) {
        if (level < 1 || level > MAX_NPC_LEVEL) throw new IllegalArgumentException("NPC 等级必须为 1～30");
        return new LinkedHashMap<>(BANDIT_LEVELS.get(level - 1).reward());
    }

    /** 保留海洋 NPC 奖励入口，直接复用陆海共用档位。 */
    public static Map<String, Integer> seaNpcReward(int level) {
        return npcReward(level);
    }

    /** 陆地和海洋 30 级 NPC 共用钻石抽取：0～9 的均匀随机值中仅 0 掉落 5 钻石。 */
    public static int npcDiamondDrop(int level, int roll) {
        if (roll < 0 || roll >= 10) throw new IllegalArgumentException("钻石抽取值必须为 0～9");
        return level == MAX_NPC_LEVEL && roll == 0 ? 5 : 0;
    }

    /** 流寇等级定义 */
    public record BanditLevel(
            int lv,
            Map<String, Integer> army,
            Map<String, Integer> reward
    ) {}

    /** NPC 只能使用仍在编制中的陆军和空军，移除存档或导入数据中的其他单位。 */
    public static Map<String, Integer> landOnlyArmy(Map<String, Integer> army) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (army != null) result.putAll(army);
        result.entrySet().removeIf(entry -> !GameData.UNITS.containsKey(entry.getKey())
                || "sea".equals(GameData.UNITS.get(entry.getKey()).branch()));
        return result;
    }
}
