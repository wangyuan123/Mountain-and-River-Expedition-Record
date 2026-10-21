-- 活动快照及发放资格与通知正文分离，不为离线账号建立空资格行。
CREATE TABLE mail_gift_activities (
    id VARCHAR(100) NOT NULL PRIMARY KEY,
    server_id VARCHAR(40) NOT NULL,
    starts_at BIGINT NOT NULL,
    ends_at BIGINT NOT NULL,
    claim_expires_at BIGINT NOT NULL,
    notice_expires_at BIGINT NOT NULL,
    cleanup_after BIGINT NOT NULL,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    new_accounts_only BOOLEAN NOT NULL DEFAULT FALSE,
    subject VARCHAR(80) NOT NULL,
    body TEXT NOT NULL,
    rewards_json TEXT NOT NULL,
    INDEX idx_gift_window(server_id,closed,starts_at,ends_at),
    INDEX idx_gift_cleanup(closed,ends_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE mail_gift_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_id VARCHAR(40) NOT NULL,
    activity_id VARCHAR(100) NOT NULL,
    player_id BIGINT NOT NULL,
    mail_id BIGINT NULL,
    issued_at BIGINT NOT NULL,
    UNIQUE KEY uk_gift_delivery(server_id,activity_id,player_id),
    INDEX idx_gift_delivery_cleanup(activity_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE pvp_match_tickets ADD COLUMN persisted_real_at BIGINT NULL;
UPDATE pvp_match_tickets SET persisted_real_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) WHERE persisted_real_at IS NULL;
ALTER TABLE pvp_match_tickets MODIFY persisted_real_at BIGINT NOT NULL;
CREATE INDEX idx_pvp_ticket_retention ON pvp_match_tickets(status,persisted_real_at,id);
ALTER TABLE forum_topics ADD COLUMN battle_report_expires_at BIGINT NULL;
UPDATE forum_topics SET battle_report_expires_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)+604800000 WHERE battle_report_id IS NOT NULL;

-- 对尚未应用的本次迁移补齐期限快照，后续配置仅影响新记录。
ALTER TABLE mails ADD COLUMN processed_retention_ms BIGINT NOT NULL DEFAULT 2592000000;
ALTER TABLE mails ADD COLUMN unread_retention_ms BIGINT NOT NULL DEFAULT 5184000000;
ALTER TABLE city_surrender_offers ADD COLUMN ended_real_at BIGINT NULL;
UPDATE city_surrender_offers SET ended_real_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
WHERE status NOT IN ('PENDING','ACCEPTED');
ALTER TABLE pvp_match_tickets ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE pvp_match_tickets SET retention_expires_at=persisted_real_at+2592000000;
ALTER TABLE pvp_match_tickets MODIFY retention_expires_at BIGINT NOT NULL;
ALTER TABLE pvp_requests ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE pvp_requests SET retention_expires_at=persisted_real_at+2592000000;
ALTER TABLE pvp_requests MODIFY retention_expires_at BIGINT NOT NULL;
ALTER TABLE pvp_practice_sessions ADD COLUMN history_retention_ms BIGINT NOT NULL DEFAULT 2592000000;
ALTER TABLE pvp_practice_sessions ADD COLUMN retention_expires_at BIGINT NULL;
UPDATE pvp_practice_sessions SET retention_expires_at=settled_real_at+2592000000 WHERE settled_real_at IS NOT NULL;
CREATE INDEX idx_pvp_session_expiry ON pvp_practice_sessions(retention_expires_at,id);
CREATE INDEX idx_pvp_ticket_expiry ON pvp_match_tickets(retention_expires_at,id);
CREATE INDEX idx_pvp_request_real_expiry ON pvp_requests(retention_expires_at,id);
CREATE INDEX idx_mail_protected_source ON mails(attachment_policy,is_claimed,attachment_source_type,persisted_real_at);
