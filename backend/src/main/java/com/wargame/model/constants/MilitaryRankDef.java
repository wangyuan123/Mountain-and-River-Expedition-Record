package com.wargame.model.constants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class MilitaryRankDef {

    private MilitaryRankDef() {}

    public record RankInfo(
            int tier,
            String name,
            int prestige,
            int baseCap,
            Map<String, Integer> reqGems
    ) {}

    public record GemDef(String key, String name, String icon, String desc) {}

    public static final Map<String, GemDef> GEMS = Map.of(
            "gem_pearl",      new GemDef("gem_pearl",      "珍珠",   "⚪", "稀有天然珍珠，野地采集获得，用于晋升军衔"),
            "gem_coral",      new GemDef("gem_coral",      "珊瑚",   "🪸", "红润天然珊瑚，野地采集获得，用于晋升军衔"),
            "gem_glaze",      new GemDef("gem_glaze",      "琉璃",   "🔮", "晶莹剔透琉璃，野地采集获得，用于晋升军衔"),
            "gem_amber",      new GemDef("gem_amber",      "琥珀",   "🍯", "温润千年琥珀，野地采集获得，用于晋升军衔"),
            "gem_agate",      new GemDef("gem_agate",      "玛瑙",   "🟤", "珍贵斑斓玛瑙，野地采集获得，用于晋升军衔"),
            "gem_crystal",    new GemDef("gem_crystal",    "水晶",   "💎", "璀璨高纯水晶，野地采集获得，用于晋升军衔"),
            "gem_jadeite",    new GemDef("gem_jadeite",    "翡翠",   "🟢", "翠绿极品翡翠，野地采集获得，用于晋升军衔"),
            "gem_jade",       new GemDef("gem_jade",       "玉石",   "🪨", "温润无瑕美玉，野地采集获得，用于晋升军衔"),
            "gem_nightpearl", new GemDef("gem_nightpearl", "夜明珠", "🌟", "绝世璀璨夜明珠，高级野地采集获得，用于晋升将官军衔")
    );

    public static final int MAX_RANK_TIER = 17;

    public static final List<RankInfo> RANKS = List.of(
            new RankInfo(1,  "列兵",   0,       50000,  Map.of()),
            new RankInfo(2,  "上等兵", 200,     100000, Map.of("gem_pearl", 3)),
            new RankInfo(3,  "下士",   500,     150000, Map.of("gem_pearl", 5, "gem_coral", 2)),
            new RankInfo(4,  "中士",   1000,    200000, Map.of("gem_pearl", 8, "gem_coral", 4, "gem_glaze", 2)),
            new RankInfo(5,  "上士",   2000,    250000, Map.of("gem_coral", 6, "gem_glaze", 4, "gem_amber", 2)),
            new RankInfo(6,  "军士长", 3500,    300000, Map.of("gem_glaze", 8, "gem_amber", 5, "gem_agate", 2)),
            new RankInfo(7,  "准尉",   5500,    350000, Map.of("gem_amber", 8, "gem_agate", 5, "gem_crystal", 2)),
            new RankInfo(8,  "少尉",   8000,    400000, Map.of("gem_agate", 8, "gem_crystal", 5, "gem_jadeite", 2)),
            new RankInfo(9,  "中尉",   15000,   450000, Map.of("gem_crystal", 8, "gem_jadeite", 5, "gem_jade", 2)),
            new RankInfo(10, "上尉",   25000,   500000, Map.of("gem_jadeite", 8, "gem_jade", 5, "gem_nightpearl", 1)),
            new RankInfo(11, "少校",   45000,   550000, Map.of("gem_jade", 8, "gem_nightpearl", 2, "gem_pearl", 15)),
            new RankInfo(12, "中校",   80000,   600000, Map.of("gem_nightpearl", 4, "gem_coral", 15, "gem_glaze", 12)),
            new RankInfo(13, "上校",   150000,  650000, Map.of("gem_amber", 15, "gem_agate", 12, "gem_crystal", 10)),
            new RankInfo(14, "大校",   300000,  700000, Map.of("gem_crystal", 15, "gem_jadeite", 12, "gem_jade", 10)),
            new RankInfo(15, "少将",   600000,  750000, Map.of("gem_jadeite", 18, "gem_jade", 15, "gem_nightpearl", 6)),
            new RankInfo(16, "中将",   1200000, 800000, Map.of("gem_jade", 20, "gem_nightpearl", 10, "gem_crystal", 15, "gem_pearl", 20)),
            new RankInfo(17, "上将",   2500000, 850000, Map.of("gem_nightpearl", 15, "gem_jade", 25, "gem_jadeite", 25, "gem_agate", 20))
    );

    public static int getCityCap(int tier) {
        if (tier < 4) return 1;
        if (tier < 7) return 2;
        if (tier < 10) return 3;
        if (tier < 13) return 4;
        if (tier < 15) return 5;
        return Math.min(8, tier - 9);
    }

    public static RankInfo nextCityRank(int tier) {
        return RANKS.stream().filter(r -> r.tier() > tier && getCityCap(r.tier()) > getCityCap(tier)).findFirst().orElse(null);
    }

    public static RankInfo getRank(int tier) {
        if (tier < 1) tier = 1;
        if (tier > MAX_RANK_TIER) tier = MAX_RANK_TIER;
        return RANKS.get(tier - 1);
    }

    public static String getRankName(int tier) {
        return getRank(tier).name();
    }

    public static int getRankBase(int tier) {
        return getRank(tier).baseCap();
    }

    /**
     * 根据野地等级随机掉落珠宝 (用于野地采集完成归城)
     */
    public static Map<String, Integer> rollGatherGems(int wildLevel) {
        Map<String, Integer> drops = new LinkedHashMap<>();
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        if (wildLevel <= 3) {
            drops.put("gem_pearl", rand.nextInt(1, 3));
            if (rand.nextDouble() < 0.35) {
                drops.put("gem_coral", 1);
            }
        } else if (wildLevel <= 6) {
            drops.put("gem_coral", rand.nextInt(1, 3));
            if (rand.nextDouble() < 0.60) drops.put("gem_glaze", 1);
            if (rand.nextDouble() < 0.40) drops.put("gem_amber", 1);
            if (rand.nextDouble() < 0.25) drops.put("gem_agate", 1);
        } else if (wildLevel <= 8) {
            drops.put("gem_agate", rand.nextInt(1, 3));
            if (rand.nextDouble() < 0.60) drops.put("gem_crystal", 1);
            if (rand.nextDouble() < 0.45) drops.put("gem_jadeite", 1);
            if (rand.nextDouble() < 0.30) drops.put("gem_jade", 1);
        } else {
            // Level 9 ~ 10
            drops.put("gem_crystal", rand.nextInt(1, 3));
            if (rand.nextDouble() < 0.70) drops.put("gem_jadeite", 1);
            if (rand.nextDouble() < 0.55) drops.put("gem_jade", 1);
            if (rand.nextDouble() < 0.35) drops.put("gem_nightpearl", 1);
        }
        return drops;
    }
}
