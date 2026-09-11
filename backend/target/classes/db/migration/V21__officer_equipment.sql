CREATE TABLE officer_equipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    officer_id BIGINT NULL,
    item_key VARCHAR(80) NOT NULL,
    set_type VARCHAR(20) NOT NULL,
    tier INT NOT NULL,
    military_bonus INT NOT NULL DEFAULT 0,
    logistics_bonus INT NOT NULL DEFAULT 0,
    knowledge_bonus INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_equipment_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_equipment_officer FOREIGN KEY (officer_id) REFERENCES officers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_equipment_player ON officer_equipment(player_id);
CREATE INDEX idx_equipment_officer ON officer_equipment(officer_id);
ALTER TABLE officers ADD COLUMN equipment_count INT NOT NULL DEFAULT 0;
