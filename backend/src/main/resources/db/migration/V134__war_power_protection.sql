CREATE TABLE player_power_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    observed_at BIGINT NOT NULL,
    formation_power BIGINT NOT NULL,
    overall_power BIGINT NOT NULL,
    INDEX idx_power_player_time (player_id, observed_at),
    INDEX idx_power_time (observed_at),
    CONSTRAINT fk_power_snapshot_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
