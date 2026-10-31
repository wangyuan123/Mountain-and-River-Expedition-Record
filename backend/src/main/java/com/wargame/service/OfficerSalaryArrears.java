package com.wargame.service;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Resources;
import java.util.List;

/** 欠薪满一分钟游戏时间后，每五秒扣2忠诚；结算频率和服务器重启不改变结果。 */
public final class OfficerSalaryArrears {
    public static final long GRACE_MILLIS = 60_000L;
    public static final long PENALTY_INTERVAL_MILLIS = 5_000L;

    private OfficerSalaryArrears() {}

    /**
     * 按本城收支估算黄金耗尽时刻，并只兑现尚未处罚的游戏时间。
     * @param intervalMillis 本次已结算经济时长，毫秒
     * @param openingGold 本次税收和薪资结算前的精确黄金（含小数余量）
     * @param closingGold 支付本次薪资后的精确余额，负值表示欠薪
     */
    public static void settle(Resources stock, List<Officer> officers, long now, long intervalMillis,
                              double openingGold, double closingGold, boolean hasSalary) {
        if (!hasSalary) {
            clear(stock);
            return;
        }
        // 同一游戏时刻的重复请求不能清空已记录的欠薪，也不能产生新处罚。
        if (intervalMillis <= 0) return;
        if (closingGold >= -1e-9) {
            clear(stock);
            return;
        }
        if (stock.getSalaryArrearsStartedAt() == null) {
            double netExpense = Math.max(0, openingGold) - closingGold;
            long coveredMillis = Math.round(intervalMillis * Math.max(0, openingGold) / netExpense);
            stock.setSalaryArrearsStartedAt(now - intervalMillis + Math.min(intervalMillis, coveredMillis));
            stock.setSalaryArrearsPenalties(0);
        }
        for (Officer officer : officers) {
            // 新军官从入职起享有完整宽限期；个人处罚边界可能与本城旧军官错开。
            long eligibleFrom = Math.max(stock.getSalaryArrearsStartedAt(),
                    officer.getSalaryGraceFrom() != null ? officer.getSalaryGraceFrom()
                            : officer.getRecruitAt() == null ? stock.getSalaryArrearsStartedAt() : officer.getRecruitAt());
            // 用实际已结算区间扣除个人已处罚次数，不能用本城最近处罚时刻替代经济时钟。
            long personalDue = penalties(now - eligibleFrom);
            long personalApplied = penalties(now - intervalMillis - eligibleFrom);
            int loss = (int) (Math.min(50, Math.max(0, personalDue - personalApplied)) * 2);
            int loyalty = officer.getLoyalty() == null ? 100 : officer.getLoyalty();
            officer.setLoyalty(Math.max(0, loyalty - loss));
        }
        stock.setSalaryArrearsPenalties(penalties(now - stock.getSalaryArrearsStartedAt()));
    }

    private static long penalties(long elapsed) {
        return elapsed < GRACE_MILLIS ? 0 : 1 + (elapsed - GRACE_MILLIS) / PENALTY_INTERVAL_MILLIS;
    }

    private static void clear(Resources stock) {
        stock.setSalaryArrearsStartedAt(null);
        stock.setSalaryArrearsPenalties(0);
    }
}
