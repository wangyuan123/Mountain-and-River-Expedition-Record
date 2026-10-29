package com.wargame.model.constants;

import java.util.List;

public final class MilitaryRankDef {

    private MilitaryRankDef() {}

    public record RankInfo(
            int tier,
            String name,
            int prestige,
            int baseCap,
            int npcVictories,
            int certificatePrice
    ) {
        public String certificateKey() { return tier > 1 ? "rank_certificate_" + tier : null; }
        public String certificateName() { return name + "晋升凭证"; }
    }

    public static final int MAX_RANK_TIER = 17;

    // 保留全部 17 阶军衔；上尉及以下每阶增加 25,000；少校基础 300,000，此后每阶增加 50,000，上将为 600,000。
    // 声望采用分段累计门槛：上等兵至军士长为 1、10、30、50、100 万；准尉起 150 万，上将 1,000 万；授衔不扣声望，不重算既有军衔。
    // 剿寇胜场为账号累计门槛：士兵与士官 10、50、110、200、300 场，准尉起 450 场，上将 6,100 场；晋升不扣胜场。
    public static final List<RankInfo> RANKS = List.of(
            new RankInfo(1,  "列兵",   0,       25000,  0, 0),
            new RankInfo(2,  "上等兵", 10000,     50000,  10, 20),
            new RankInfo(3,  "下士",   100000,     75000,  50, 40),
            new RankInfo(4,  "中士",   300000,    100000, 110, 60),
            new RankInfo(5,  "上士",   500000,    125000, 200, 80),
            new RankInfo(6,  "军士长", 1000000,    150000, 300, 100),
            new RankInfo(7,  "准尉",   1500000,    175000, 450, 150),
            new RankInfo(8,  "少尉",   2000000,    200000, 650, 200),
            new RankInfo(9,  "中尉",   2600000,   225000, 900, 300),
            new RankInfo(10, "上尉",   3300000,   250000, 1200, 400),
            new RankInfo(11, "少校",   4100000,   300000, 1600, 600),
            new RankInfo(12, "中校",   5000000,   350000, 2100, 800),
            new RankInfo(13, "上校",   6000000,  400000, 2700, 1000),
            new RankInfo(14, "大校",   7000000,  450000, 3400, 1200),
            new RankInfo(15, "少将",   8000000,  500000, 4200, 1500),
            new RankInfo(16, "中将",   9000000, 550000, 5100, 2000),
            new RankInfo(17, "上将",   10000000, 600000, 6100, 2500)
    );

    /** 精确匹配商城凭证 key，拒绝越界阶数、前导零及其他伪造商品。 */
    public static RankInfo certificateRank(String itemKey) {
        return RANKS.stream().filter(r -> r.tier() > 1 && r.certificateKey().equals(itemKey)).findFirst().orElse(null);
    }

    /** 商城、主线和仓库复用来源说明；NPC 战斗不再产出晋升凭证。 */
    public static String certificateSource(RankInfo rank) {
        return "商城「晋升凭证」购买，" + rank.certificatePrice() + " 钻石/张；NPC 不再掉落";
    }

    /** 含主城；前期连续扩城，尉官六城、校官七城、将官八城，按已授衔阶数生效。 */
    public static int getCityCap(int tier) {
        if (tier < 2) return 1;
        if (tier < 3) return 2;
        if (tier < 4) return 3;
        if (tier < 6) return 4;
        if (tier < 8) return 5;
        if (tier < 11) return 6;
        if (tier < 15) return 7;
        return 8;
    }

    /** 跳过城市上限不变的军衔，返回下一次扩城所需军衔；已开放八城时返回 null。 */
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

}
