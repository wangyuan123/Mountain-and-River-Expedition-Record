CREATE TABLE IF NOT EXISTS army_production_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    player_id BIGINT NOT NULL,
    unit_type VARCHAR(50) NOT NULL,
    unit_count INT NOT NULL,
    started_at BIGINT NOT NULL,
    finishes_at BIGINT NOT NULL,
    duration_seconds INT NOT NULL,
    speed_multiplier DECIMAL(10,3) NOT NULL,
    cost_food INT NOT NULL DEFAULT 0,
    cost_steel INT NOT NULL DEFAULT 0,
    cost_oil INT NOT NULL DEFAULT 0,
    cost_rare INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_army_queue_player_finish (player_id, finishes_at),
    CONSTRAINT fk_army_queue_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);
