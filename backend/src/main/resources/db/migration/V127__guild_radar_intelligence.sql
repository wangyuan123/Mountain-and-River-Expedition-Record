ALTER TABLE player_cities ADD COLUMN radar_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE player_cities ADD COLUMN radar_revision BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_city_radar_enabled ON player_cities (radar_enabled, owner_id);

CREATE TABLE radar_monitors (
    player_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO radar_monitors (player_id, version, revision) SELECT id, 0, 0 FROM players;

CREATE TABLE radar_contacts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    observer_id BIGINT NOT NULL,
    march_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    attacker_id BIGINT NOT NULL,
    target_key VARCHAR(160) NOT NULL,
    threat_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    read_at BIGINT NOT NULL DEFAULT 0,
    notice_pending BOOLEAN NOT NULL DEFAULT FALSE,
    notice_at BIGINT NOT NULL DEFAULT 0,
    reminder_group VARCHAR(36) NOT NULL,
    snapshot LONGTEXT NOT NULL,
    sources TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT uk_radar_observer_march UNIQUE (observer_id, march_id)
);
CREATE INDEX idx_radar_observer_time ON radar_contacts (observer_id, first_seen_at);
CREATE INDEX idx_radar_expiration ON radar_contacts (status, expires_at, id);
