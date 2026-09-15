CREATE TABLE wounded_units (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    city_id BIGINT NULL,
    city_x INT NOT NULL,
    city_y INT NOT NULL,
    city_name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    count INT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    recovery_percent INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_wounded_player_expiry (player_id, expires_at),
    INDEX idx_wounded_expiry (expires_at)
);
