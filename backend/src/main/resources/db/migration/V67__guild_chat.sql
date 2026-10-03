-- V67: persistent guild chat messages
CREATE TABLE IF NOT EXISTS guild_chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    player_id BIGINT NULL,
    username VARCHAR(50) NOT NULL,
    content VARCHAR(80) NOT NULL,
    created_at BIGINT NOT NULL,
    KEY idx_guild_chat_guild_created (guild_id, created_at),
    CONSTRAINT fk_guild_chat_guild FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE,
    CONSTRAINT fk_guild_chat_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
