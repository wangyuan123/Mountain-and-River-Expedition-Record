-- 固定上线时已初始化账号的军衔；新注册或待初始化账号只领取列兵启程礼包。
ALTER TABLE players ADD COLUMN rank_gift_baseline_tier INT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN rank_gifts_enrolled BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE players SET rank_gift_baseline_tier = LEAST(17, GREATEST(1, COALESCE(military_rank, 1)))
WHERE game_initialized = TRUE;

CREATE TABLE rank_gift_rewards (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    rank_tier INT NOT NULL,
    item_key VARCHAR(40) NOT NULL,
    diamonds INT NOT NULL,
    source VARCHAR(20) NOT NULL,
    issued_at BIGINT NOT NULL,
    opened_at BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_rank_gift_player_tier UNIQUE (player_id, rank_tier)
);
