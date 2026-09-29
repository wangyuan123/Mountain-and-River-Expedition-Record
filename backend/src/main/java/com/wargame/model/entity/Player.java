package com.wargame.model.entity;

import com.wargame.model.constants.AvatarDef;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalDate;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "players")
public class Player extends VersionedEntity implements CityEconomy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "active_city_id")
    private Long activeCityId;

    /** 账号可先注册，只有实名及游戏许可通过后才初始化角色；旧存档默认已初始化。 */
    @Column(name = "game_initialized", nullable = false)
    private boolean gameInitialized = true;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "faction", length = 20)
    private String faction = "allies";

    @Column(name = "city_name", length = 100)
    private String cityName = "";

    @Column(name = "avatar", length = 255)
    private String avatar = AvatarDef.randomAvatar();

    /** 首页四个模块的账号级顺序，JSON 数组；null 表示尚未自定义。 */
    @Column(name = "home_module_order", length = 128)
    private String homeModuleOrder;

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

    @Column(name = "military_rank")
    private Integer militaryRank = 1;

    @Column(name = "vip_level")
    private Integer vipLevel = 0;

    @Column(name = "last_tick")
    private Long lastTick = 0L;

    /** 军校刷新额度按账号共享，切换城市不能重新获得次数。 */
    @Column(name = "academy_refresh_round_count", nullable = false)
    private int academyRefreshRoundCount = 0;

    @Column(name = "academy_refresh_daily_count", nullable = false)
    private int academyRefreshDailyCount = 0;

    @Column(name = "academy_refresh_day")
    private LocalDate academyRefreshDay;

    /** 仅本轮用满30次时设置冷却结束时间，跨日不提前解除冷却。 */
    @Column(name = "academy_refresh_at", nullable = false)
    private long academyRefreshAt = 0L;

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

    /** 注销生命周期独立于登录态；恢复期限受理后不随配置变化。 */
    @Column(name = "account_status", nullable = false, length = 24)
    private String accountStatus = "ACTIVE";

    @Column(name = "recover_until", nullable = false)
    private Long recoverUntil = 0L;

    @Column(name = "deleted_at", nullable = false)
    private Long deletedAt = 0L;

    @Column(name = "auth_version", nullable = false)
    private Long authVersion = 0L;

    @Column(name = "deletion_request_id", length = 64)
    private String deletionRequestId;

    /** 仅存恢复凭据摘要，单次消费并绑定本次注销版本。 */
    @Column(name = "recovery_token_hash", length = 64)
    private String recoveryTokenHash;

    @Column(name = "recovery_token_expires_at", nullable = false)
    private Long recoveryTokenExpiresAt = 0L;

    public boolean accountActive() {
        return "ACTIVE".equals(accountStatus) && !Integer.valueOf(1).equals(disabled);
    }

    public boolean deletionDue(long now) {
        return "DELETED".equals(accountStatus) ||
                ("PENDING_DELETION".equals(accountStatus) && now >= recoverUntil);
    }

    /** 是否已跳过新手引导，避免刷新或跨设备后重复弹窗 */
    @Column(name = "tutorial_dismissed")
    private Boolean tutorialDismissed = false;

    /** 玩家按兵种设置的离线出城/守城战术；未设置的兵种沿用兵种默认值。 */
    @Column(name = "outgoing_battle_actions", columnDefinition = "text")
    private String outgoingBattleActions;

    @Column(name = "defending_battle_actions", columnDefinition = "text")
    private String defendingBattleActions;

    /** 账号级迎战编队；null 为按驻军比例自动选兵，空对象为不派野战部队。 */
    @Column(name = "sortie_army", columnDefinition = "text")
    private String sortieArmy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Integer getMilitaryRank() {
        return militaryRank != null ? militaryRank : 1;
    }

    public void setMilitaryRank(Integer militaryRank) {
        this.militaryRank = militaryRank;
    }
}
