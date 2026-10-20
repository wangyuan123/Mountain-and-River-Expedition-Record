package com.wargame.model.constants;

import java.util.Map;

/** 军校与军事理论各提供最高 3 个百分点；五星名将共用一次整批判定。 */
public final class AcademyRecruitmentRules {
    public static final String MILITARY_THEORY = "military_theory";
    private static final int MAX_LEVEL = 10;

    private AcademyRecruitmentRules() {}

    private static int level(int value) {
        return Math.max(0, Math.min(MAX_LEVEL, value));
    }

    /** 返回普通刷新整批概率；没有军校时，共享科技不能独立提供招募能力。 */
    public static double fiveStarBatchChance(int academyLevel, int theoryLevel) {
        return academyLevel <= 0 ? 0 : (level(academyLevel) + level(theoryLevel)) * 3 / 1000.0;
    }

    /** 状态与刷新响应共用概率拆分；表示下一次刷新，不追溯已生成名单。 */
    public static Map<String, Object> state(int academyLevel, int theoryLevel) {
        return Map.of(
                "baseFiveStarBatchChance", level(academyLevel) * 3 / 1000.0,
                "militaryTheoryLevel", level(theoryLevel),
                "militaryTheoryBonus", level(theoryLevel) * 3 / 1000.0,
                "fiveStarBatchChance", fiveStarBatchChance(academyLevel, theoryLevel));
    }
}
