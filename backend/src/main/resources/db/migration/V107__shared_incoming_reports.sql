CREATE TABLE shared_incoming_reports (
    token VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    data LONGTEXT NOT NULL,
    CONSTRAINT fk_shared_incoming_owner FOREIGN KEY (owner_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_shared_incoming_guild FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
