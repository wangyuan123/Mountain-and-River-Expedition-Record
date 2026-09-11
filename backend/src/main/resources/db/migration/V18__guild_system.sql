-- 军团系统：军团、成员与申请
CREATE TABLE guilds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(16) NOT NULL,
    notice VARCHAR(200) NOT NULL DEFAULT '',
    leader_player_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_guilds_name (name),
    KEY idx_guilds_leader (leader_player_id),
    CONSTRAINT fk_guilds_leader FOREIGN KEY (leader_player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE guild_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    joined_at BIGINT NOT NULL,
    UNIQUE KEY uk_guild_members_player (player_id),
    UNIQUE KEY uk_guild_members_pair (guild_id, player_id),
    KEY idx_guild_members_guild (guild_id),
    CONSTRAINT fk_guild_members_guild FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE,
    CONSTRAINT fk_guild_members_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE guild_applications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_guild_applications_pair (guild_id, player_id),
    KEY idx_guild_applications_guild (guild_id),
    CONSTRAINT fk_guild_applications_guild FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE,
    CONSTRAINT fk_guild_applications_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
