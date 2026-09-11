package com.wargame.model.constants;

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

    public static final List<String> BANDIT_NAMES = List.of(
            "流寇营地", "残兵游勇", "马匪哨所", "叛军据点",
            "山贼窝点", "溃兵残部", "武装走私队", "雇佣兵营"
    );

    public static final List<BanditLevel> BANDIT_LEVELS = List.of(
            new BanditLevel(1,
                    Map.of("infantry", 20),
                    Map.of("food", 80, "steel", 120, "oil", 60, "rare", 10, "gold", 15, "exp", 15)),
            new BanditLevel(2,
                    Map.of("infantry", 30, "motor", 8),
                    Map.of("food", 120, "steel", 180, "oil", 90, "rare", 15, "gold", 20, "exp", 25)),
            new BanditLevel(3,
                    Map.of("motor", 15, "armored", 6),
                    Map.of("food", 180, "steel", 260, "oil", 140, "rare", 25, "gold", 30, "exp", 40)),
            new BanditLevel(4,
                    Map.of("ltank", 10, "armored", 8),
                    Map.of("food", 240, "steel", 360, "oil", 200, "rare", 40, "gold", 45, "exp", 60)),
            new BanditLevel(5,
                    Map.of("htank", 6, "assault", 4, "fighter", 4),
                    Map.of("food", 320, "steel", 480, "oil", 280, "rare", 60, "gold", 70, "exp", 90)),
            new BanditLevel(6,
                    Map.of("htank", 10, "rocket", 6, "bomber", 4),
                    Map.of("food", 440, "steel", 660, "oil", 400, "rare", 90, "gold", 100, "exp", 130)),
            new BanditLevel(7,
                    Map.of("htank", 16, "rocket", 10, "fighter", 10, "sub", 4),
                    Map.of("food", 600, "steel", 900, "oil", 560, "rare", 130, "gold", 150, "exp", 180)),
            new BanditLevel(8,
                    Map.of("battleship", 4, "carrier", 1, "fighter", 20),
                    Map.of("food", 800, "steel", 1200, "oil", 760, "rare", 180, "gold", 220, "exp", 250))
    );

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
}
