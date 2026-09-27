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

    public static final int SIZE = 200;
    public static final int VIEW_RADIUS = 3;
    public static final int MARCH_SEC_PER_GRID = 9;
        public static final int MAX_NPC_LEVEL = 30;

    public static final List<String> BANDIT_NAMES = List.of(
            "日寇前哨", "日寇营地", "日寇炮楼", "日寇据点",
            "日寇补给站", "雇佣兵营", "武装走私队", "雇佣兵基地"
    );

        public static final List<BanditLevel> BANDIT_LEVELS = createBanditLevels();

        private static List<BanditLevel> createBanditLevels() {
                List<BanditLevel> levels = new ArrayList<>(List.of(
            new BanditLevel(1,
                    Map.of("infantry", 20),
                    Map.of("food", 80, "steel", 120, "oil", 60, "rare", 10, "gold", 15, "exp", 15)),
            new BanditLevel(2,
                    Map.of("infantry", 30, "motor", 10),
                    Map.of("food", 120, "steel", 180, "oil", 90, "rare", 15, "gold", 20, "exp", 25)),
            new BanditLevel(3,
                    Map.of("infantry", 35, "motor", 15, "armored", 8),
                    Map.of("food", 180, "steel", 260, "oil", 140, "rare", 25, "gold", 30, "exp", 40)),
            new BanditLevel(4,
                    Map.of("infantry", 40, "motor", 15, "armored", 10, "ltank", 10),
                    Map.of("food", 240, "steel", 360, "oil", 200, "rare", 40, "gold", 45, "exp", 60)),
            new BanditLevel(5,
                    Map.of("infantry", 45, "motor", 15, "armored", 10, "ltank", 12, "htank", 8, "assault", 6, "fighter", 6),
                    Map.of("food", 320, "steel", 480, "oil", 280, "rare", 60, "gold", 70, "exp", 90)),
            new BanditLevel(6,
                    Map.of("infantry", 50, "motor", 20, "armored", 12, "ltank", 15, "htank", 12, "assault", 8, "rocket", 8, "fighter", 10, "bomber", 6),
                    Map.of("food", 440, "steel", 660, "oil", 400, "rare", 90, "gold", 100, "exp", 130)),
            new BanditLevel(7,
                    Map.of("infantry", 55, "motor", 20, "armored", 15, "ltank", 18, "htank", 16, "assault", 12, "rocket", 12, "fighter", 15, "bomber", 8, "special", 8),
                    Map.of("food", 600, "steel", 900, "oil", 560, "rare", 130, "gold", 150, "exp", 180)),
            new BanditLevel(8,
                    Map.of("infantry", 60, "motor", 25, "armored", 20, "ltank", 20, "htank", 20, "assault", 15, "rocket", 15, "fighter", 20, "bomber", 10, "special", 12),
                    Map.of("food", 800, "steel", 1200, "oil", 760, "rare", 180, "gold", 220, "exp", 250))
        ));

        for (int level = 9; level <= MAX_NPC_LEVEL; level++) {
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
            rew.put("exp", level * 60);
            if (level == MAX_NPC_LEVEL) {
                rew.put("diamond", 20); // 最高等级NPC掉落钻石
            }

            levels.add(new BanditLevel(level, army, rew));
        }
        // 只放大兵力和常规可掠夺物资；经验与钻石维持原值。
        return levels.stream().map(base -> {
            Map<String, Integer> army = new LinkedHashMap<>();
            base.army().forEach((unit, count) -> army.put(unit, count * 3));
            Map<String, Integer> reward = new LinkedHashMap<>();
            base.reward().forEach((resource, amount) ->
                    reward.put(resource, ("exp".equals(resource) || "diamond".equals(resource)) ? amount : amount * 3));
            return new BanditLevel(base.lv(), Map.copyOf(army), Map.copyOf(reward));
        }).toList();
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
