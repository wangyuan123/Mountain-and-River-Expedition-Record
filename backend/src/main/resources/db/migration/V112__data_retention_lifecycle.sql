-- 生命周期全部使用服务端真实 Unix 毫秒时间；旧游戏战报只获得从迁移时刻起的完整过渡期。
SET @retention_now_ms = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

ALTER TABLE chat_messages
    ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE chat_messages
SET retention_expires_at = GREATEST(created_at + 604800000, @retention_now_ms + 604800000)
WHERE retention_expires_at IS NULL;
ALTER TABLE chat_messages MODIFY retention_expires_at BIGINT NOT NULL;
CREATE INDEX idx_chat_retention_expiry ON chat_messages(retention_expires_at, id);

ALTER TABLE guild_chat_messages
    ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE guild_chat_messages
SET retention_expires_at = GREATEST(created_at + 2592000000, @retention_now_ms + 604800000)
WHERE retention_expires_at IS NULL;
ALTER TABLE guild_chat_messages MODIFY retention_expires_at BIGINT NOT NULL;
CREATE INDEX idx_guild_chat_retention_expiry ON guild_chat_messages(retention_expires_at, id);

ALTER TABLE private_chat_messages
    ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE private_chat_messages
SET retention_expires_at = GREATEST(created_at + 7776000000, @retention_now_ms + 604800000)
WHERE retention_expires_at IS NULL;
ALTER TABLE private_chat_messages MODIFY retention_expires_at BIGINT NOT NULL;
CREATE INDEX idx_private_retention_expiry ON private_chat_messages(retention_expires_at, id);

ALTER TABLE mails ADD COLUMN persisted_real_at BIGINT NULL;
ALTER TABLE mails ADD COLUMN processed_real_at BIGINT NULL;
ALTER TABLE mails ADD COLUMN retention_expires_at BIGINT NULL;
ALTER TABLE mails ADD COLUMN attachment_policy VARCHAR(20) NOT NULL DEFAULT 'PROTECTED';
ALTER TABLE mails ADD COLUMN attachment_source_type VARCHAR(40) NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE mails ADD COLUMN attachment_source_id VARCHAR(100) NULL;
ALTER TABLE mails ADD COLUMN attachment_expires_at BIGINT NULL;
ALTER TABLE mails ADD COLUMN attachment_expired_at BIGINT NULL;
ALTER TABLE mails ADD COLUMN cleanup_after BIGINT NULL;
UPDATE mails
SET persisted_real_at = @retention_now_ms,
    attachment_policy = CASE
        WHEN COALESCE(attach_json, '[]') IN ('', '[]', 'null') OR is_claimed = TRUE THEN 'NONE'
        ELSE 'PROTECTED'
    END,
    attachment_source_type = CASE
        WHEN COALESCE(attach_json, '[]') IN ('', '[]', 'null') OR is_claimed = TRUE THEN 'NONE'
        ELSE 'LEGACY_UNKNOWN'
    END,
    processed_real_at = CASE
        WHEN is_read = TRUE AND (COALESCE(attach_json, '[]') IN ('', '[]', 'null') OR is_claimed = TRUE)
             AND surrender_offer_id IS NULL THEN @retention_now_ms
        ELSE NULL
    END,
    retention_expires_at = CASE
        WHEN surrender_offer_id IS NOT NULL THEN NULL
        WHEN is_read = TRUE AND (COALESCE(attach_json, '[]') IN ('', '[]', 'null') OR is_claimed = TRUE)
             THEN @retention_now_ms + 2592000000
        WHEN COALESCE(attach_json, '[]') IN ('', '[]', 'null') OR is_claimed = TRUE
             THEN GREATEST(created_at + 5184000000, @retention_now_ms + 604800000)
        ELSE NULL
    END
WHERE persisted_real_at IS NULL;
ALTER TABLE mails MODIFY persisted_real_at BIGINT NOT NULL;
CREATE INDEX idx_mail_retention_expiry ON mails(retention_expires_at, id);
CREATE INDEX idx_mail_attachment_expiry ON mails(attachment_policy, attachment_expires_at, id);

ALTER TABLE scout_reports ADD COLUMN persisted_real_at BIGINT NULL;
ALTER TABLE scout_reports ADD COLUMN detail_expires_at BIGINT NULL;
ALTER TABLE scout_reports ADD COLUMN summary_expires_at BIGINT NULL;
ALTER TABLE scout_reports ADD COLUMN summary_json LONGTEXT NULL;
ALTER TABLE scout_reports ADD COLUMN list_summary_json TEXT NULL;
ALTER TABLE scout_reports ADD COLUMN summary_schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE scout_reports ADD COLUMN starred_at BIGINT NULL;
UPDATE scout_reports
SET persisted_real_at = @retention_now_ms,
    detail_expires_at = @retention_now_ms + 2592000000,
    summary_expires_at = @retention_now_ms + 5184000000
WHERE persisted_real_at IS NULL;
CREATE INDEX idx_report_detail_expiry ON scout_reports(detail_expires_at, id);
CREATE INDEX idx_report_summary_expiry ON scout_reports(summary_expires_at, id);
CREATE INDEX idx_report_owner_cursor ON scout_reports(player_id, created_at, id);

ALTER TABLE shared_reports
    ADD COLUMN expires_at BIGINT NULL;
UPDATE shared_reports SET expires_at = @retention_now_ms + 604800000
WHERE expires_at IS NULL;
ALTER TABLE shared_reports MODIFY expires_at BIGINT NOT NULL;
CREATE INDEX idx_shared_report_expiry ON shared_reports(expires_at, token);

ALTER TABLE shared_incoming_reports
    ADD COLUMN expires_at BIGINT NULL;
UPDATE shared_incoming_reports SET expires_at = @retention_now_ms + 604800000
WHERE expires_at IS NULL;
ALTER TABLE shared_incoming_reports MODIFY expires_at BIGINT NOT NULL;
CREATE INDEX idx_shared_incoming_expiry ON shared_incoming_reports(expires_at, token);

ALTER TABLE pvp_practice_reports ADD COLUMN persisted_real_at BIGINT NULL;
ALTER TABLE pvp_practice_reports ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE pvp_practice_reports
SET persisted_real_at = @retention_now_ms,
    retention_expires_at = @retention_now_ms + 2592000000
WHERE persisted_real_at IS NULL;
ALTER TABLE pvp_practice_reports MODIFY persisted_real_at BIGINT NOT NULL;
ALTER TABLE pvp_practice_reports MODIFY retention_expires_at BIGINT NOT NULL;
CREATE INDEX idx_pvp_report_retention ON pvp_practice_reports(retention_expires_at, player_id, session_id);

ALTER TABLE pvp_practice_sessions ADD COLUMN settled_real_at BIGINT NULL;
UPDATE pvp_practice_sessions
SET settled_real_at = @retention_now_ms
WHERE settled_at > 0 AND settled_real_at IS NULL;
CREATE INDEX idx_pvp_session_retention ON pvp_practice_sessions(status, settled_real_at, id);

ALTER TABLE pvp_requests ADD COLUMN persisted_real_at BIGINT NULL;
UPDATE pvp_requests SET persisted_real_at = @retention_now_ms WHERE persisted_real_at IS NULL;
ALTER TABLE pvp_requests MODIFY persisted_real_at BIGINT NOT NULL;
CREATE INDEX idx_pvp_request_retention ON pvp_requests(persisted_real_at, id);

CREATE TABLE data_retention_control (
    control_id INT NOT NULL PRIMARY KEY,
    observation_started_at BIGINT NOT NULL,
    lease_owner VARCHAR(36) NULL,
    lease_until BIGINT NOT NULL DEFAULT 0,
    last_run_at BIGINT NOT NULL DEFAULT 0,
    cleanup_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    next_category INT NOT NULL DEFAULT 0,
    backfill_cursor BIGINT NOT NULL DEFAULT 0,
    detail_cursor BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO data_retention_control (control_id, observation_started_at, cleanup_enabled)
VALUES (1, @retention_now_ms, FALSE);

CREATE INDEX idx_mail_recipient_cursor ON mails(to_player_id, created_at, id);
CREATE INDEX idx_mail_sender_cursor ON mails(from_player_id, created_at, id);
CREATE INDEX idx_guild_chat_cursor ON guild_chat_messages(guild_id, created_at, id);
CREATE INDEX idx_private_sender_cursor ON private_chat_messages(sender_id, recipient_id, id);
CREATE INDEX idx_private_recipient_cursor ON private_chat_messages(recipient_id, sender_id, id);
