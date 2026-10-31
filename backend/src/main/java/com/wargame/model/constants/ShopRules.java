package com.wargame.model.constants;

/** 商城报价和扣费共用数量、限购与总额规则；调用者负责在事务内读取并锁定账号库存。 */
public final class ShopRules {
    private ShopRules() {}

    public static long cost(int price, int count) {
        if (count < 1 || count > 999) throw new IllegalArgumentException("购买数量无效");
        long total = Math.multiplyExact((long) price, count);
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("购买总额超出上限");
        return total;
    }

    public static String unavailable(String id, int count, int rank, int owned, boolean stockRecordExists) {
        var certificate = MilitaryRankDef.certificateRank(id);
        if (certificate != null) {
            if (count != 1) return "晋升凭证每次只能购买1张";
            if (certificate.tier() <= rank) return "已获得该军衔，无需购买对应晋升凭证";
            if (owned > 0) return "已持有该晋升凭证，无需重复购买";
        }
        if ("newbiePack".equals(id) && (count != 1 || stockRecordExists)) return "新手礼包每个账号限购1份";
        return "";
    }
}
