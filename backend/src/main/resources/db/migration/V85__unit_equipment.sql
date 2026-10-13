CREATE TABLE unit_loadouts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    unit_type VARCHAR(30) NOT NULL,
    slots TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_unit_loadout (player_id, unit_type)
);
CREATE TABLE unit_equipment_orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    city_slot INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    item_key VARCHAR(50) NOT NULL,
    cost TEXT NOT NULL,
    start_at BIGINT NOT NULL,
    end_at BIGINT NOT NULL,
    INDEX idx_equipment_lane (player_id, city_slot, start_at)
);
ALTER TABLE marches ADD COLUMN unit_equipment_snapshot LONGTEXT NULL;
ALTER TABLE battle_sessions ADD COLUMN defender_equipment_sources LONGTEXT NULL;
