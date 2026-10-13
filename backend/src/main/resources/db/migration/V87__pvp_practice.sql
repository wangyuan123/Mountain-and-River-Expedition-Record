-- 演习副本独立持久化，不关联行军，不写真实战损与奖励。

CREATE TABLE pvp_player_states (
    player_id BIGINT PRIMARY KEY,
    active_ticket_id VARCHAR(36),
    active_session_id VARCHAR(36),
    last_session_id VARCHAR(36),
    preset LONGTEXT,
    preset_version BIGINT NOT NULL DEFAULT 0,
    rating INT NOT NULL DEFAULT 1000,
    games INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    draws INT NOT NULL DEFAULT 0,
    recent_opponents TEXT,
    confirmation_window BIGINT NOT NULL DEFAULT 0,
    confirmation_exits INT NOT NULL DEFAULT 0,
    cooldown_until BIGINT NOT NULL DEFAULT 0,
    sequence_no BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pvp_match_tickets (
    id VARCHAR(36) PRIMARY KEY,
    player_id BIGINT,
    region VARCHAR(40),
    battlefield VARCHAR(20),
    snapshot LONGTEXT,
    snapshot_hash VARCHAR(64),
    rules_version VARCHAR(64),
    power_model_version VARCHAR(80),
    power BIGINT NOT NULL DEFAULT 0,
    rating INT NOT NULL DEFAULT 0,
    placement BOOLEAN NOT NULL DEFAULT 0,
    queued_at BIGINT NOT NULL DEFAULT 0,
    lease_until BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20),
    session_id VARCHAR(36),
    accepted BOOLEAN NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_pvp_queue ON pvp_match_tickets(region, battlefield, status, power, rating, queued_at);

CREATE INDEX idx_pvp_ticket_due ON pvp_match_tickets(status, lease_until, queued_at);

CREATE TABLE pvp_practice_sessions (
    id VARCHAR(36) PRIMARY KEY,
    player_a BIGINT,
    player_b BIGINT,
    ticket_a VARCHAR(36),
    ticket_b VARCHAR(36),
    snapshot_a LONGTEXT,
    snapshot_b LONGTEXT,
    status VARCHAR(20),
    deadline_at BIGINT NOT NULL DEFAULT 0,
    starts_at BIGINT NOT NULL DEFAULT 0,
    round_no INT NOT NULL DEFAULT 0,
    loaded_a BOOLEAN NOT NULL DEFAULT 0,
    loaded_b BOOLEAN NOT NULL DEFAULT 0,
    seed BIGINT NOT NULL DEFAULT 0,
    rules_version VARCHAR(64),
    battle_data LONGTEXT,
    result VARCHAR(20),
    reason VARCHAR(100),
    settled_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_pvp_session_due ON pvp_practice_sessions(status, deadline_at);

CREATE TABLE pvp_round_orders (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36),
    player_id BIGINT,
    round_no INT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    orders TEXT,
    locked BOOLEAN NOT NULL DEFAULT 0,
    locked_at BIGINT NOT NULL DEFAULT 0,
    source VARCHAR(20),
    request_id VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_pvp_order ON pvp_round_orders(session_id, round_no, player_id);

CREATE TABLE pvp_round_results (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36),
    round_no INT NOT NULL DEFAULT 0,
    seed BIGINT NOT NULL DEFAULT 0,
    data LONGTEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_pvp_round ON pvp_round_results(session_id, round_no);

CREATE TABLE pvp_practice_reports (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36),
    player_id BIGINT,
    created_at BIGINT NOT NULL DEFAULT 0,
    result VARCHAR(20),
    data LONGTEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_pvp_report ON pvp_practice_reports(session_id, player_id);

CREATE INDEX idx_pvp_history ON pvp_practice_reports(player_id, created_at);

CREATE TABLE pvp_requests (
    id VARCHAR(36) PRIMARY KEY,
    player_id BIGINT,
    request_id VARCHAR(64),
    payload_hash VARCHAR(64),
    response LONGTEXT,
    created_at BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_pvp_request ON pvp_requests(player_id, request_id);

CREATE INDEX idx_pvp_request_expiry ON pvp_requests(created_at);

CREATE TABLE pvp_power_calibrations (
    id VARCHAR(64) PRIMARY KEY,
    model_version VARCHAR(64),
    signature VARCHAR(200),
    factor DOUBLE NOT NULL DEFAULT 0,
    evidence LONGTEXT,
    version BIGINT NOT NULL DEFAULT 0
);
