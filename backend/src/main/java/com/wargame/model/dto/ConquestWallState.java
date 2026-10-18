package com.wargame.model.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 本场征服战独立墙体快照；不属于军队，不参与整数击杀、伤兵或经验结算。 */
public record ConquestWallState(int level, BigDecimal maxHp, BigDecimal hpRemaining,
                                int defense, String phase, String rulesVersion) {
    public static final String TARGET = "wall";
    public static final String CLEAR_DEFENDERS = "CLEAR_DEFENDERS";
    public static final String ATTACK_WALL = "ATTACK_WALL";
    public static final String BREACHED = "BREACHED";
    public static final int HP_PER_LEVEL = 250_000;
    public static final int DEFENSE_PER_LEVEL = 20;

    public ConquestWallState {
        maxHp = maxHp.setScale(6, RoundingMode.HALF_UP);
        hpRemaining = hpRemaining.setScale(6, RoundingMode.HALF_UP);
    }

    /** 开战冻结等级并生成满血墙；历史超过满级的建筑统一按10级战斗属性处理。 */
    public static ConquestWallState create(long level, boolean defendersAlive, String rulesVersion) {
        return create(level, 0, defendersAlive, rulesVersion);
    }

    /** 开战冻结守方加固科技；墙体不读取部队生命或防御科技。 */
    public static ConquestWallState create(long level, int reinforcement, boolean defendersAlive, String rulesVersion) {
        int frozenLevel = (int) Math.max(0, Math.min(10, level));
        double multiplier = 1 + .05 * Math.max(0, Math.min(10, reinforcement));
        BigDecimal hp = BigDecimal.valueOf((long) HP_PER_LEVEL * frozenLevel).multiply(BigDecimal.valueOf(multiplier)).setScale(6, RoundingMode.HALF_UP);
        return new ConquestWallState(frozenLevel, hp, hp, (int) Math.round(DEFENSE_PER_LEVEL * frozenLevel * multiplier),
                hp.signum() == 0 ? BREACHED : defendersAlive ? CLEAR_DEFENDERS : ATTACK_WALL, rulesVersion);
    }

    public boolean standing() { return hpRemaining.signum() > 0; }

    /** 使用六位小数连续扣血并封顶，展示取整不得反馈到后续回合计算。 */
    public ConquestWallState damage(double damage) {
        BigDecimal applied = BigDecimal.valueOf(Math.max(0, damage)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal remaining = hpRemaining.subtract(applied).max(BigDecimal.ZERO).setScale(6);
        return new ConquestWallState(level, maxHp, remaining, defense,
                remaining.signum() == 0 ? BREACHED : ATTACK_WALL, rulesVersion);
    }

    public ConquestWallState afterRound(boolean defendersAlive) {
        return new ConquestWallState(level, maxHp, hpRemaining, defense,
                !standing() ? BREACHED : defendersAlive ? CLEAR_DEFENDERS : ATTACK_WALL, rulesVersion);
    }
}
