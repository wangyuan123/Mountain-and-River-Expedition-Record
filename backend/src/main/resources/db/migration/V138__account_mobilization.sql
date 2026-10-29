ALTER TABLE players ADD COLUMN mobilization_expiries TEXT NULL;
ALTER TABLE players ADD COLUMN mobilization_sortie_army TEXT NULL;
ALTER TABLE players ADD COLUMN mobilization_cycle BIGINT NOT NULL DEFAULT 0;

CREATE TABLE mobilization_uses (
    id VARCHAR(100) NOT NULL PRIMARY KEY,
    player_id BIGINT NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    amount INT NOT NULL,
    used_at BIGINT NOT NULL,
    INDEX idx_mobilization_uses_player (player_id)
);
