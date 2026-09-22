-- 军团外交关系：同一对军团按 ID 升序保存一条共享关系。
CREATE TABLE guild_relations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_low_id BIGINT NOT NULL,
    guild_high_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_by_player_id BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_guild_relations_pair (guild_low_id, guild_high_id),
    KEY idx_guild_relations_low (guild_low_id),
    KEY idx_guild_relations_high (guild_high_id),
    CONSTRAINT fk_guild_relations_low FOREIGN KEY (guild_low_id) REFERENCES guilds(id) ON DELETE CASCADE,
    CONSTRAINT fk_guild_relations_high FOREIGN KEY (guild_high_id) REFERENCES guilds(id) ON DELETE CASCADE,
    CONSTRAINT fk_guild_relations_updated_by FOREIGN KEY (updated_by_player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT chk_guild_relations_status CHECK (status IN ('hostile', 'friendly')),
    CONSTRAINT chk_guild_relations_pair CHECK (guild_low_id < guild_high_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
