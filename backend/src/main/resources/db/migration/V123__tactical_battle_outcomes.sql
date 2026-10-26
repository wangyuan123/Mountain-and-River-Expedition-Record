-- 会话与返城行军均可清理，终局按原参战身份独立保留30天。
CREATE TABLE tactical_battle_outcomes (
    march_id BIGINT NOT NULL PRIMARY KEY,
    attacker_id BIGINT NOT NULL,
    defender_id BIGINT NULL,
    attacker_view LONGTEXT NOT NULL,
    defender_view LONGTEXT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_tactical_outcome_expiry (expires_at)
);
