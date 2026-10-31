package com.wargame.model.constants;

/** 军官升级经验：保留前20级成本，在20、50、80级后叠加二次项，阶段交界平滑增长。 */
public final class OfficerExperienceRules {
    public static final int MAX_LEVEL = 100;
    public static final int RAPID_BOOK_EXPERIENCE = 500_000;
    public static final int RAPID_BOOK_PRICE = 500;

    private OfficerExperienceRules() {}

    /** 满级书按从一级升满所需的整本极速书总价打八折，不按军官当前等级浮动。 */
    public static int maxBookPrice() {
        long experience = 0L;
        for (int level = 1; level < MAX_LEVEL; level++) experience += expNeeded(level);
        long books = (experience + RAPID_BOOK_EXPERIENCE - 1) / RAPID_BOOK_EXPERIENCE;
        return Math.toIntExact(books * RAPID_BOOK_PRICE * 8 / 10);
    }

    public record Growth(int level, long exp, int upgraded) {}

    /** 预览与一键升级共用经验扣减顺序，满级时清空剩余经验。 */
    public static Growth preview(int level, long experience) {
        int initial = level;
        while (level < MAX_LEVEL && experience >= expNeeded(level)) {
            experience -= expNeeded(level);
            level++;
        }
        return new Growth(level, level >= MAX_LEVEL ? 0 : experience, level - initial);
    }

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
