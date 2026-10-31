package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "resources")
public class Resources extends CityOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "food")
    private Long food;

    @Column(name = "steel")
    private Long steel;

    @Column(name = "oil")
    private Long oil;

    @Column(name = "rare")
    private Long rare;

    @Column(name = "gold")
    private Long gold;

    @Column(name = "diamond")
    private Integer diamond;

    // 自然收支的小数累计到下次结算，避免5秒调度把低等级产量或少量养兵消耗反复舍掉。
    @Column(name = "food_accrual", nullable = false)
    private double foodAccrual;
    @Column(name = "steel_accrual", nullable = false)
    private double steelAccrual;
    @Column(name = "oil_accrual", nullable = false)
    private double oilAccrual;
    @Column(name = "rare_accrual", nullable = false)
    private double rareAccrual;
    @Column(name = "gold_accrual", nullable = false)
    private double goldAccrual;

    /** 本城连续欠薪起点，使用游戏时钟；补足当期薪资后清空。 */
    @Column(name = "salary_arrears_started_at")
    private Long salaryArrearsStartedAt;

    /** 已兑现的欠薪处罚次数，重启、重登及同一时刻重复结算均不重复扣忠诚。 */
    @Column(name = "salary_arrears_penalties", nullable = false)
    private long salaryArrearsPenalties;

    public void setFood(Long value) { this.food = clampNullable(value); }
    public void setFood(Integer value) { this.food = value == null ? null : Math.max(0L, value.longValue()); }
    public void setSteel(Long value) { this.steel = clampNullable(value); }
    public void setSteel(Integer value) { this.steel = value == null ? null : Math.max(0L, value.longValue()); }
    public void setOil(Long value) { this.oil = clampNullable(value); }
    public void setOil(Integer value) { this.oil = value == null ? null : Math.max(0L, value.longValue()); }
    public void setRare(Long value) { this.rare = clampNullable(value); }
    public void setRare(Integer value) { this.rare = value == null ? null : Math.max(0L, value.longValue()); }
    public void setGold(Long value) { this.gold = clampNullable(value); }
    public void setGold(Integer value) { this.gold = value == null ? null : Math.max(0L, value.longValue()); }

    public void addResource(String type, long amount) {
        switch (type) {
            case "food" -> setFood(cappedAdd(food, amount));
            case "steel" -> setSteel(cappedAdd(steel, amount));
            case "oil" -> setOil(cappedAdd(oil, amount));
            case "rare" -> setRare(cappedAdd(rare, amount));
            case "gold" -> setGold(cappedAdd(gold, amount));
            default -> throw new IllegalArgumentException("未知资源类型: " + type);
        }
    }

    public static long cappedAdd(Long current, long amount) {
        long base = current != null ? current.longValue() : 0L;
        if (amount > 0 && amount > Long.MAX_VALUE - base) return Long.MAX_VALUE;
        if (amount < 0 && amount < -base) return 0;
        return Math.max(0L, base + amount);
    }

    private static Long clampNullable(Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    @PrePersist
    @PreUpdate
    private void clampPersistedStock() {
        food = clampNullable(food);
        steel = clampNullable(steel);
        oil = clampNullable(oil);
        rare = clampNullable(rare);
        gold = clampNullable(gold);
    }
    /** 钻石钱包不可保存负数，溢出时由事务整体撤销本次发奖。 */
    public void setDiamond(Integer value) {
        if (value != null && value < 0) throw new IllegalArgumentException("钻石余额超出可保存范围");
        diamond = value;
    }

}
