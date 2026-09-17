-- 既有注销账号按原默认 7 天期限迁移；到期账号由清理作业处理。
ALTER TABLE players ADD COLUMN account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE players ADD COLUMN recover_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN deleted_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN deletion_request_id VARCHAR(64) NULL;
ALTER TABLE players ADD COLUMN recovery_token_hash VARCHAR(64) NULL;
ALTER TABLE players ADD COLUMN recovery_token_expires_at BIGINT NOT NULL DEFAULT 0;
UPDATE players SET account_status = 'PENDING_DELETION',
    recover_until = COALESCE(disabled_at, 0) + 604800000, auth_version = 1
    WHERE disabled = 1;
CREATE INDEX idx_players_deletion_due ON players(account_status, recover_until, id);
