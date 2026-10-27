package com.wargame.model.constants;

/** 军官升级经验：保留前20级成本，在20、50、80级后叠加二次项，阶段交界平滑增长。 */
public final class OfficerExperienceRules {
    public static final int MAX_LEVEL = 100;

    private OfficerExperienceRules() {}

    /** 当前等级升到下一级的需求；满级不再消耗经验。与前端 G.expNeeded 保持一致。 */
    public static long expNeeded(int level) {
        if (level >= MAX_LEVEL) return 0L;
        int currentLevel = Math.max(1, level);
        long after20 = Math.max(0, currentLevel - 20);
        long after50 = Math.max(0, currentLevel - 50);
        long after80 = Math.max(0, currentLevel - 80);
        return 200L * currentLevel + 75L * after20 * after20
                + 150L * after50 * after50 + 300L * after80 * after80;
    }
}
