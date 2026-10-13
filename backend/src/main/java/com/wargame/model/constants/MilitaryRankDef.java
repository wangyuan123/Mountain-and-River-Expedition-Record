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
            int npcVictories,
            int minNpcLevel,
            int certificateDropPercent
    ) {
        public String certificateKey() { return tier > 1 ? "rank_certificate_" + tier : null; }
        public String certificateName() { return name + "晋升凭证"; }
    }

    public record GemDef(String key, String name, String icon, String desc) {}

    public static final Map<String, GemDef> GEMS = Map.of(
            "gem_pearl",      new GemDef("gem_pearl",      "珍珠",   "⚪", "稀有天然珍珠，野地采集获得，可收藏的珠宝珍品"),
            "gem_coral",      new GemDef("gem_coral",      "珊瑚",   "🪸", "红润天然珊瑚，野地采集获得，可收藏的珠宝珍品"),
            "gem_glaze",      new GemDef("gem_glaze",      "琉璃",   "🔮", "晶莹剔透琉璃，野地采集获得，可收藏的珠宝珍品"),
            "gem_amber",      new GemDef("gem_amber",      "琥珀",   "🍯", "温润千年琥珀，野地采集获得，可收藏的珠宝珍品"),
            "gem_agate",      new GemDef("gem_agate",      "玛瑙",   "🟤", "珍贵斑斓玛瑙，野地采集获得，可收藏的珠宝珍品"),
            "gem_crystal",    new GemDef("gem_crystal",    "水晶",   "💎", "璀璨高纯水晶，野地采集获得，可收藏的珠宝珍品"),
            "gem_jadeite",    new GemDef("gem_jadeite",    "翡翠",   "🟢", "翠绿极品翡翠，野地采集获得，可收藏的珠宝珍品"),
            "gem_jade",       new GemDef("gem_jade",       "玉石",   "🪨", "温润无瑕美玉，野地采集获得，可收藏的珠宝珍品"),
            "gem_nightpearl", new GemDef("gem_nightpearl", "夜明珠", "🌟", "绝世璀璨夜明珠，高级野地采集获得，可收藏的珠宝珍品")
    );

    public static final int MAX_RANK_TIER = 17;

    // 保留全部 17 阶军衔；上尉及以下每阶增加 25,000；少校基础 300,000，此后每阶增加 50,000，上将为 600,000。
    public static final List<RankInfo> RANKS = List.of(
            new RankInfo(1,  "列兵",   0,       25000,  0,    0,  0),
            new RankInfo(2,  "上等兵", 200,     50000,  3,    1,  30),
            new RankInfo(3,  "下士",   500,     75000,  8,    2,  28),
            new RankInfo(4,  "中士",   1000,    100000, 15,   3,  26),
            new RankInfo(5,  "上士",   2000,    125000, 25,   4,  24),
            new RankInfo(6,  "军士长", 3500,    150000, 40,   5,  22),
            new RankInfo(7,  "准尉",   5500,    175000, 60,   6,  20),
            new RankInfo(8,  "少尉",   8000,    200000, 90,   8,  18),
            new RankInfo(9,  "中尉",   15000,   225000, 130,  10, 16),
            new RankInfo(10, "上尉",   25000,   250000, 180,  12, 14),
            new RankInfo(11, "少校",   45000,   300000, 250,  14, 12),
            new RankInfo(12, "中校",   80000,   350000, 350,  16, 10),
            new RankInfo(13, "上校",   150000,  400000, 480,  18, 9),
            new RankInfo(14, "大校",   300000,  450000, 650,  21, 8),
            new RankInfo(15, "少将",   600000,  500000, 850,  24, 6),
            new RankInfo(16, "中将",   1200000, 550000, 1100, 27, 4),
            new RankInfo(17, "上将",   2500000, 600000, 1400, 30, 2)
    );

    /** NPC 等级只匹配一个凭证档位；高级 NPC 不混入低阶凭证。 */
    public static RankInfo certificateRankForNpc(int npcLevel) {
        if (npcLevel < 1 || npcLevel > WorldConfig.MAX_NPC_LEVEL) return null;
        for (int i = RANKS.size() - 1; i > 0; i--) {
            if (npcLevel >= RANKS.get(i).minNpcLevel()) return RANKS.get(i);
        }
        return null;
    }

    /** 每场胜利抽一次，成功只掉落一张；roll 为 [0, 1) 的均匀随机数。 */
    public static String rollPromotionCertificate(int npcLevel, double roll) {
        if (!Double.isFinite(roll) || roll < 0 || roll >= 1) throw new IllegalArgumentException("无效的掉落随机值");
        RankInfo rank = certificateRankForNpc(npcLevel);
        return rank != null && roll < rank.certificateDropPercent() / 100.0 ? rank.certificateKey() : null;
    }

    public static int maxNpcLevelForCertificate(int tier) {
        return tier >= MAX_RANK_TIER ? WorldConfig.MAX_NPC_LEVEL : getRank(tier + 1).minNpcLevel() - 1;
    }

    /** 主线与仓库复用同一来源文案，防止 NPC 等级区间和掉率漂移。 */
    public static String certificateSource(RankInfo rank) {
        int maxLevel = maxNpcLevelForCertificate(rank.tier());
        String levels = rank.minNpcLevel() == maxLevel ? String.valueOf(maxLevel) : rank.minNpcLevel() + "–" + maxLevel;
        return levels + " 级寇或海域守军，胜利掉率 " + rank.certificateDropPercent() + "%";
    }

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
