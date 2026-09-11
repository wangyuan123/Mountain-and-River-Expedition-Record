package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "faction", length = 20)
    private String faction = "allies";

    @Column(name = "city_name", length = 100)
    private String cityName = "";

    @Column(name = "pos_x")
    private Integer posX = 0;

    @Column(name = "pos_y")
    private Integer posY = 0;

    @Column(name = "city_pos_x")
    private Integer cityPosX = 0;

    @Column(name = "city_pos_y")
    private Integer cityPosY = 0;

    @Column(name = "tax")
    private Integer tax = 30;

    @Column(name = "morale")
    private Integer morale = 70;

    @Column(name = "resentment")
    private Integer resentment = 0;

    @Column(name = "last_appease_at")
    private Long lastAppeaseAt = 0L;

    @Column(name = "prestige")
    private Integer prestige = 0;

    @Column(name = "level")
    private Integer level = 1;

    @Column(name = "vip_level")
    private Integer vipLevel = 0;

    @Column(name = "last_tick")
    private Long lastTick = 0L;

    @Column(name = "civilian_population")
    private Integer civilianPopulation = 0;

    @Column(name = "population_growth_remainder")
    private Double populationGrowthRemainder = 0.0;

    /** 备战结束、进入交战的时间戳 (毫秒) */
    @Column(name = "war_at")
    private Long warAt = 0L;

    /** 战争结束时间戳 (毫秒) */
    @Column(name = "war_end_at")
    private Long warEndAt = 0L;

    /** 当前与之宣战的真实玩家 player.id，NULL 表示无 */
    @Column(name = "war_against_id")
    private Long warAgainstId = null;

    /** 账号是否已注销（软删除标记），0=正常，1=已注销 */
    @Column(name = "disabled")
    private Integer disabled = 0;

    /** 账号注销时间（毫秒），用于 7 天宽限期判断 */
    @Column(name = "disabled_at")
    private Long disabledAt = 0L;

    /** 是否已跳过新手引导，避免刷新或跨设备后重复弹窗 */
    @Column(name = "tutorial_dismissed")
    private Boolean tutorialDismissed = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
