-- 恢复邮箱只在验证码通过后占用；NULL 兼容全部历史未绑定账号。
ALTER TABLE players ADD COLUMN recovery_email_cipher VARCHAR(512) NULL;
ALTER TABLE players ADD COLUMN recovery_email_key VARCHAR(64) NULL;
ALTER TABLE players ADD COLUMN recovery_email_verified_at BIGINT NULL;
ALTER TABLE players ADD COLUMN recovery_email_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_players_recovery_email ON players (recovery_email_key);

-- 不再保存可直接登录的明文凭据，历史迁移保持不变。
ALTER TABLE players DROP COLUMN plain_password;
UPDATE admin_operation_logs SET detail = '历史密码重置记录（凭据内容已清理）'
WHERE action_type = 'RESET_PLAYER_PASSWORD';

CREATE TABLE account_email_gate (id INT PRIMARY KEY);
INSERT INTO account_email_gate (id) VALUES (1);
CREATE TABLE account_email_quotas (
    id VARCHAR(80) PRIMARY KEY, used INT NOT NULL, expires_at BIGINT NOT NULL,
    INDEX idx_email_quota_expiry (expires_at)
);
CREATE TABLE account_email_challenges (
    slot VARCHAR(64) PRIMARY KEY, challenge_id VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(32) NOT NULL, player_id BIGINT NULL, username VARCHAR(50) NULL,
    email_cipher VARCHAR(512), email_key VARCHAR(64), code_hash VARCHAR(64),
    auth_version BIGINT NOT NULL, email_version BIGINT NOT NULL, flow_hash VARCHAR(64),
    expires_at BIGINT NOT NULL, sent BOOLEAN NOT NULL, failures INT NOT NULL, consumed BOOLEAN NOT NULL,
    INDEX idx_email_challenge_expiry (expires_at), INDEX idx_email_challenge_player (player_id)
);
CREATE TABLE account_email_changes (
    token_hash VARCHAR(64) PRIMARY KEY, player_id BIGINT NOT NULL,
    email_cipher VARCHAR(512) NOT NULL, email_key VARCHAR(64) NOT NULL,
    auth_version BIGINT NOT NULL, email_version BIGINT NOT NULL, expires_at BIGINT NOT NULL,
    INDEX idx_email_change_expiry (expires_at), INDEX idx_email_change_player (player_id)
);
CREATE TABLE account_email_outbox (
    id VARCHAR(64) PRIMARY KEY, challenge_id VARCHAR(64), player_id BIGINT NULL,
    email_cipher VARCHAR(512), body_cipher VARCHAR(4096), subject VARCHAR(200),
    expires_at BIGINT NOT NULL, next_attempt_at BIGINT NOT NULL, attempts INT NOT NULL, state INT NOT NULL,
    INDEX idx_email_outbox_due (state, next_attempt_at), INDEX idx_email_outbox_expiry (expires_at),
    INDEX idx_email_outbox_player (player_id)
);
