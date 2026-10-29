package com.wargame.model.constants;

/** 礼包金额由下一阶凭证售价推导；最高军衔使用独立荣耀奖励。 */
public final class RankGiftDef {
    private RankGiftDef() {}
    public static final int GLORY_DIAMONDS = 5000;
    public static final String ICON = "img/items/newbie-pack.svg";

    public static String key(int tier) { return "rank_diamond_pack_" + tier; }
    public static String name(int tier) {
        return tier == 1 ? "列兵启程钻石礼包" : tier == MilitaryRankDef.MAX_RANK_TIER
                ? "上将荣耀钻石大礼包" : MilitaryRankDef.getRankName(tier) + "晋升钻石大礼包";
    }
    public static int diamonds(int tier) {
        return tier == MilitaryRankDef.MAX_RANK_TIER ? GLORY_DIAMONDS
                : MilitaryRankDef.getRank(tier + 1).certificatePrice();
    }
    /** 精确白名单，拒绝前导零、越界档位和伪造 key。 */
    public static Integer tier(String itemKey) {
        return MilitaryRankDef.RANKS.stream().map(MilitaryRankDef.RankInfo::tier)
                .filter(t -> key(t).equals(itemKey)).findFirst().orElse(null);
    }
    public static String description(int tier, int diamonds) {
        return "开启获得" + diamonds + "钻石，计入账号共享余额；" + (tier == MilitaryRankDef.MAX_RANK_TIER
                ? "最高军衔荣耀奖励" : "可用于购买" + MilitaryRankDef.getRank(tier + 1).certificateName());
    }
}
