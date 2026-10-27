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

    /** null 为待首次进入的新账号，0 为不补发保护的历史账号。 */
    @Column(name = "newbie_protection_started_at")
    private Long newbieProtectionStartedAt = 0L;

    /** 主动参战或发展达标后永久结束，切城、重登及声望下降均不恢复。 */
    @Column(name = "newbie_protection_ended", nullable = false)
    private boolean newbieProtectionEnded = false;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    /** 玩家在游戏内展示的统帅名；登录和收件人查找仍使用 username。 */
    @Column(name = "display_name", length = 100)
    private String displayName;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "display_name_key", unique = true, length = 200)
    private String displayNameKey;

    /** 仅记录本机制上线后成功的用卡改名；历史没有可靠时间，不伪造冷却。 */
    @Column(name = "commander_renamed_at", nullable = false)
    private long commanderRenamedAt = 0L;

    @Column(name = "commander_rename_count", nullable = false)
    private int commanderRenameCount = 0;

    /** 与邮件生命周期分离，邮件删除、已读或重复初始化均不能重新赠卡。 */
    @Column(name = "commander_rename_gift_sent", nullable = false)
    private boolean commanderRenameGiftSent = false;

    @PrePersist
    @PreUpdate
    public void updateDisplayNameKey() {
        displayNameKey = com.wargame.model.constants.CommanderRenamePolicy.nameKey(
                displayName == null || displayName.isBlank() ? username : displayName);
    }

    /** 标记是否为系统批量生成的模拟玩家 (Bot) */
    @Column(name = "is_bot", nullable = false)
    private Boolean isBot = false;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "faction", length = 20)
    private String faction = "allies";

    @Column(name = "city_name", length = 100)
    private String cityName = "";

    @Column(name = "city_name_renamed_at", nullable = false)
    private long cityNameRenamedAt = 0L;

    @Column(name = "avatar", length = 255)
    private String avatar = AvatarDef.randomAvatar();

    /** 好友添加权限：APPROVAL 需审批、ANYONE 可直接添加、NONE 拒绝新申请。 */
    @Column(name = "friend_request_policy", nullable = false, length = 16)
    private String friendRequestPolicy = "APPROVAL";

    /** 开启后仅接收自己好友列表中玩家的私聊，系统和军团消息不受影响。 */
    @Column(name = "block_stranger_messages", nullable = false)
    private boolean blockStrangerMessages = false;

    /** 首页四个模块的账号级顺序，JSON 数组；null 表示尚未自定义。 */
    @Column(name = "home_module_order", length = 128)
    private String homeModuleOrder;

    /** 账号级坐标收藏快照；固定保存坐标，城市搬迁后不跟随目标移动。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "map_favorites", columnDefinition = "TEXT")
    private String mapFavorites;

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

    /** 账号累计击败流寇/NPC 要塞的胜利场次，晋升时不扣除。 */
    @Column(name = "npc_victories", nullable = false)
    private long npcVictories = 0L;

    /** 服务端保存目标军衔、试卷及成绩，禁止序列化泄露标准答案。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "rank_exam", columnDefinition = "TEXT")
    private String rankExam;

    @Column(name = "vip_level")
    private Integer vipLevel = 0;

    @Column(name = "total_recharge_cents", nullable = false)
    private Long totalRechargeCents = 0L;

    @Column(name = "custom_avatar", length = 512)
    private String customAvatar;

    @Column(name = "avatar_frame", nullable = false, length = 64)
    private String avatarFrame = "default";

    @Column(name = "last_vip_daily_reward_date")
    private LocalDate lastVipDailyRewardDate;

    @Column(name = "vip_badge_visible", nullable = false)
    private boolean vipBadgeVisible = true;

    @Column(name = "stored_daily_salaries_count", nullable = false)
    private int storedDailySalariesCount = 0;

    @Column(name = "last_free_amnesty_date")
    private LocalDate lastFreeAmnestyDate;

    @Column(name = "last_daily_checkin_date")
    private LocalDate lastDailyCheckinDate;

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

    @Column(name = "war_declared_at")
    private Long warDeclaredAt;

    @Column(name = "war_initiator_id")
    private Long warInitiatorId;

    @Column(name = "war_city_slot")
    private Integer warCitySlot;

    @Column(name = "war_target_city_id")
    private Long warTargetCityId;

    @Column(name = "war_accelerated", nullable = false)
    private boolean warAccelerated = false;

    @Column(name = "war_order_effect", columnDefinition = "text")
    private String warOrderEffect;

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

    /** 封禁状态：NORMAL / BANNED */
    @Column(name = "ban_status", nullable = false, length = 20)
    private String banStatus = "NORMAL";

    /** 封号原因 */
    @Column(name = "ban_reason", length = 255)
    private String banReason;

    /** 解封时间戳 (毫秒)，0 或 NULL 表示永久封禁 */
    @Column(name = "banned_until")
    private Long bannedUntil;

    public boolean isBanned(long now) {
        if (!"BANNED".equalsIgnoreCase(banStatus)) return false;
        if (bannedUntil == null || bannedUntil <= 0L) return true;
        return now < bannedUntil;
    }

    public boolean accountActive() {
        return "ACTIVE".equals(accountStatus) && !Integer.valueOf(1).equals(disabled) && !isBanned(System.currentTimeMillis());
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

    /** 所有声望写入统一记录新手毕业，先检查旧值以防通过降声望重新开盾。 */
    public void setPrestige(Integer prestige) {
        long now = com.wargame.util.GameClock.now();
        com.wargame.model.constants.NewbieProtection.finishForPrestige(this, now);
        this.prestige = prestige;
        com.wargame.model.constants.NewbieProtection.finishForPrestige(this, now);
    }

    public void setMilitaryRank(Integer militaryRank) {
        this.militaryRank = militaryRank;
    }

    public boolean isVipBadgeVisible() {
        return vipBadgeVisible;
    }

    public boolean getVipBadgeVisible() {
        return vipBadgeVisible;
    }
}
