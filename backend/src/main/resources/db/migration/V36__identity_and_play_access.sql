-- 实名与防沉迷独立于游戏存档；不保存姓名、证件明文或可重用会话密钥。
ALTER TABLE players ADD COLUMN game_initialized BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE identity_subjects (
    id VARCHAR(64) PRIMARY KEY,
    birth_cipher VARCHAR(128) NOT NULL,
    verified_until BIGINT NOT NULL,
    guardian_id VARCHAR(64),
    consent_version VARCHAR(80),
    daily_limit_seconds INT NOT NULL DEFAULT 3600,
    end_minute INT NOT NULL DEFAULT 1260,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    chat_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    active_session VARCHAR(64),
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_play_seconds CHECK (daily_limit_seconds BETWEEN 0 AND 3600),
    CONSTRAINT chk_play_end CHECK (end_minute BETWEEN 1200 AND 1260)
);
CREATE TABLE player_identities (
    player_id BIGINT PRIMARY KEY,
    subject_id VARCHAR(64) NOT NULL,
    verified_at BIGINT NOT NULL,
    CONSTRAINT fk_identity_player FOREIGN KEY (player_id) REFERENCES players(id),
    CONSTRAINT fk_identity_subject FOREIGN KEY (subject_id) REFERENCES identity_subjects(id)
);
CREATE INDEX idx_identity_subject ON player_identities(subject_id);
CREATE TABLE play_sessions (
    id VARCHAR(64) PRIMARY KEY,
    subject_id VARCHAR(64) NOT NULL,
    player_id BIGINT NOT NULL,
    auth_version BIGINT NOT NULL,
    started_at BIGINT NOT NULL,
    accounted_through BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    allowed_until BIGINT NOT NULL,
    ended_at BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_play_player ON play_sessions(player_id);
CREATE TABLE play_usage_daily (
    id VARCHAR(80) PRIMARY KEY,
    subject_id VARCHAR(64) NOT NULL,
    play_date VARCHAR(10) NOT NULL,
    used_millis BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE compliance_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_compliance_event_time ON compliance_events(created_at);
CREATE TABLE protection_requests (
    id VARCHAR(36) PRIMARY KEY,
    player_id BIGINT NOT NULL,
    request_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_protection_player ON protection_requests(player_id);
