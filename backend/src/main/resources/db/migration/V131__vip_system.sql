-- 1. 玩家表补充累计充值金额(分)、自定义头像、头像框、日常领奖标记、聊天名牌显隐、军饷暂存天数与每日免费大赦标记
ALTER TABLE players ADD COLUMN total_recharge_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN custom_avatar VARCHAR(512) DEFAULT NULL;
ALTER TABLE players ADD COLUMN avatar_frame VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE players ADD COLUMN last_vip_daily_reward_date DATE DEFAULT NULL;
ALTER TABLE players ADD COLUMN vip_badge_visible BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE players ADD COLUMN stored_daily_salaries_count INT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN last_free_amnesty_date DATE DEFAULT NULL;

-- 2. VIP 晋级礼包领取记录表 (每个玩家每个等级仅能领取一次，硬约束防刷)
CREATE TABLE player_vip_rewards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    vip_level INT NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_player_vip_level (player_id, vip_level),
    CONSTRAINT fk_player_vip_reward_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. VIP 阶段微里程碑军备箱领取记录表 (记录高阶充值每 ¥500/¥1,000 阶段军备箱领取状态)
CREATE TABLE player_vip_milestones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    milestone_cents BIGINT NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_player_milestone (player_id, milestone_cents),
    CONSTRAINT fk_player_vip_milestone_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
