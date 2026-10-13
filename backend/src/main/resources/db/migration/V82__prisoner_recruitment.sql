-- 新账号科技默认0；不迁移历史战损，也不向历史战报补发俘虏。
CREATE TABLE battle_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_kind VARCHAR(30) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    player_id BIGINT NOT NULL,
    city_id BIGINT NULL,
    created_at BIGINT NOT NULL,
    snapshot LONGTEXT NULL,
    CONSTRAINT uk_battle_settlement UNIQUE (source_kind, source_id)
);
CREATE INDEX idx_battle_settlement_player ON battle_settlements(player_id);
CREATE TABLE prisoner_units (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    city_slot INT NOT NULL DEFAULT 0,
    settlement_id BIGINT NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    original_owner_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    original_count INT NOT NULL,
    count INT NOT NULL,
    created_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_prisoner_batch UNIQUE (settlement_id, source_id, type)
);
CREATE INDEX idx_prisoner_city ON prisoner_units(player_id, city_id, created_at, id);
CREATE TABLE prisoner_operations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    operation_key VARCHAR(80) NOT NULL,
    action VARCHAR(20) NOT NULL,
    type VARCHAR(50) NOT NULL,
    count INT NOT NULL,
    created_at BIGINT NOT NULL,
    result LONGTEXT NOT NULL,
    CONSTRAINT uk_prisoner_operation UNIQUE (player_id, operation_key)
);
