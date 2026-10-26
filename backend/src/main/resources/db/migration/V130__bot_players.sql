CREATE TABLE bot_profiles (
    player_id BIGINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    pvp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    city_slot INT NOT NULL DEFAULT 0,
    interval_seconds INT NOT NULL DEFAULT 45,
    reserve_percent INT NOT NULL DEFAULT 35,
    max_marches INT NOT NULL DEFAULT 2,
    army_target INT NOT NULL DEFAULT 500,
    next_action_at BIGINT NOT NULL DEFAULT 0,
    action_count BIGINT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    last_action VARCHAR(40) NOT NULL DEFAULT 'WAIT',
    last_reason VARCHAR(500) NOT NULL DEFAULT '',
    last_target VARCHAR(100) NOT NULL DEFAULT '',
    target_cooldown_until BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_bot_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);
CREATE INDEX idx_bot_due ON bot_profiles(enabled, next_action_at, player_id);

CREATE TABLE bot_action_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    game_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    success BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    detail TEXT NOT NULL,
    CONSTRAINT fk_bot_log_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);
CREATE INDEX idx_bot_log_player ON bot_action_logs(player_id, id);
