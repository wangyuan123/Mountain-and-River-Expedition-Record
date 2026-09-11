-- V8: persistent world chat messages
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    username VARCHAR(50) NOT NULL,
    content VARCHAR(80) NOT NULL,
    created_at BIGINT NOT NULL,
    KEY idx_chat_created_at (created_at),
    CONSTRAINT fk_chat_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
