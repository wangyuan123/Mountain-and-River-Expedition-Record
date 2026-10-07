package com.wargame.model.constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 世界配置 - 对应 data.js 中 G.DATA.world
 */
public final class WorldConfig {

    private WorldConfig() {}

    /** 开放战区及地形索引为 400×400；四象限展示世界为 800×800。 */
    public static final int SIZE = 400;
    public static final int WORLD_SIZE = SIZE * 2;
    public static final int VIEW_RADIUS = 3;
    public static final int MARCH_SEC_PER_GRID = 9;
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
        base.put("infantry", level <= 10 ? (level <= 3 ? level * 5 : 18 + (level - 4) * 4) : 48 + (level - 11) * 6);
        if (level >= 4) base.put("motor", level <= 10 ? (level - 3) * 2 : 14 + (level - 11) * 2);
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

    /** 海洋 NPC 统一守军：舰载机仅在 21 级解锁航母后出现。 */
    public static Map<String, Integer> seaNpcGarrison(int level) {
        if (level < 1 || level > MAX_NPC_LEVEL) throw new IllegalArgumentException("海洋 NPC 等级必须为 1～30");
        int multiplier = level + 9;
        Map<String, Integer> army = new LinkedHashMap<>();
        army.put("sub", 40 * level * multiplier);
        if (level >= 4) {
            int destroyers = level <= 10 ? 12 * (level - 3)
                    : level <= 20 ? 84 + 18 * (level - 10) : 264 + 24 * (level - 20);
            army.put("destroyer", destroyers * multiplier);
        }
        if (level >= 11) {
            int battleships = level <= 20 ? 8 * (level - 10) : 80 + 12 * (level - 20);
            army.put("battleship", battleships * multiplier);
        }
        if (level >= 21) {
            int carrierStep = level - 20;
            // 航母使用独立倍率 50～59；舰载机仍使用本等级的普通倍率。
            army.put("carrier", 2 * carrierStep * (level + 29));
            army.put("fighter", 80 * carrierStep * multiplier);
            army.put("bomber", 40 * carrierStep * multiplier);
        }
        return army;
    }

    public static final List<String> BANDIT_NAMES = List.of(
            "日寇前哨", "日寇营地", "日寇炮楼", "日寇据点",
            "日寇补给站", "雇佣兵营", "武装走私队", "雇佣兵基地"
    );

        public static final List<BanditLevel> BANDIT_LEVELS = createBanditLevels();

        private static List<BanditLevel> createBanditLevels() {
                List<BanditLevel> levels = new ArrayList<>(List.of(
            new BanditLevel(1,
                    Map.of("infantry", 60),
                    Map.of("food", 80, "steel", 120, "oil", 60, "rare", 10, "gold", 15)),
            new BanditLevel(2,
                    Map.of("infantry", 90, "motor", 30),
                    Map.of("food", 120, "steel", 180, "oil", 90, "rare", 15, "gold", 20)),
            new BanditLevel(3,
                    Map.of("infantry", 105, "motor", 45),
                    Map.of("food", 180, "steel", 260, "oil", 140, "rare", 25, "gold", 30)),
            new BanditLevel(4,
                    Map.of("infantry", 120, "motor", 45),
                    Map.of("food", 240, "steel", 360, "oil", 200, "rare", 40, "gold", 45)),
            new BanditLevel(5,
                    Map.of("infantry", 135, "motor", 45),
                    Map.of("food", 320, "steel", 480, "oil", 280, "rare", 60, "gold", 70)),
            new BanditLevel(6,
                    Map.of("infantry", 150, "motor", 60, "armored", 12),
                    Map.of("food", 440, "steel", 660, "oil", 400, "rare", 90, "gold", 100)),
            new BanditLevel(7,
                    Map.of("infantry", 165, "motor", 60, "armored", 15, "ltank", 18),
                    Map.of("food", 600, "steel", 900, "oil", 560, "rare", 130, "gold", 150)),
            new BanditLevel(8,
                    Map.of("infantry", 180, "motor", 75, "armored", 20, "ltank", 20, "assault", 15),
                    Map.of("food", 800, "steel", 1200, "oil", 760, "rare", 180, "gold", 220))
        ));

        // 9、10 级逐级解锁重坦和火箭炮；11 级起采用全兵种公式。
        levels.add(new BanditLevel(9,
                Map.of("infantry", 195, "motor", 90, "armored", 25, "ltank", 25, "assault", 18, "htank", 8),
                Map.of("food", 1440, "steel", 2160, "oil", 1260, "rare", 405, "gold", 540)));
        levels.add(new BanditLevel(10,
                Map.of("infantry", 210, "motor", 105, "armored", 30, "ltank", 30, "assault", 21, "htank", 10, "rocket", 8),
                Map.of("food", 1600, "steel", 2400, "oil", 1400, "rare", 450, "gold", 600)));
        for (int level = 11; level <= MAX_NPC_LEVEL; level++) {
            Map<String, Integer> army = new LinkedHashMap<>();
            army.put("infantry", level * 6);
            army.put("motor", level * 2);
            army.put("armored", level * 2);
            army.put("ltank", level * 2);
            army.put("htank", level * 2);
            army.put("assault", level);
            army.put("rocket", level);
            army.put("fighter", level);
            army.put("bomber", Math.max(1, level / 2));
            army.put("special", Math.max(1, level / 2));

            Map<String, Integer> rew = new LinkedHashMap<>();
            rew.put("food", level * 160);
            rew.put("steel", level * 240);
            rew.put("oil", level * 140);
            rew.put("rare", level * 45);
            rew.put("gold", level * 60);
            levels.add(new BanditLevel(level, army, rew));
        }
        // 1～10 级的基础兵力按等级逐级乘 10～19；11～30 级在历史三倍基数上追加三十倍。
        // 陆海 NPC 资源按品类放大；经验按击杀结算，30 级钻石只在胜利时独立抽取。
        return levels.stream().map(base -> {
            Map<String, Integer> army = new LinkedHashMap<>();
            int multiplier = base.lv() <= 10 ? base.lv() + 9 : 90;
            base.army().forEach((unit, count) -> army.put(unit, count * multiplier));
            Map<String, Integer> reward = new LinkedHashMap<>();
            base.reward().forEach((resource, amount) -> {
                int rewardMultiplier = "rare".equals(resource) ? 45 : "gold".equals(resource) ? 15 : 30;
                reward.put(resource, amount * rewardMultiplier);
            });
            return new BanditLevel(base.lv(), Map.copyOf(army), Map.copyOf(reward));
        }).toList();
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

    public static final List<String> NPC_CITY_NAMES = List.of(
            "汉堡", "华沙", "维也纳", "布鲁塞尔", "阿姆斯特丹", "斯德哥尔摩", "奥斯陆", "哥本哈根",
            "布拉格", "布达佩斯", "贝尔格莱德", "索菲亚", "布加勒斯特", "赫尔辛基", "都柏林", "里斯本"
    );

    /** 流寇等级定义 */
    public record BanditLevel(
            int lv,
            Map<String, Integer> army,
            Map<String, Integer> reward
    ) {}

    /** NPC 只能使用陆军和空军，移除存档或导入数据中的海军单位。 */
    public static Map<String, Integer> landOnlyArmy(Map<String, Integer> army) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (army != null) result.putAll(army);
        result.remove("destroyer");
        result.remove("sub");
        result.remove("battleship");
        result.remove("carrier");
        return result;
    }
}
