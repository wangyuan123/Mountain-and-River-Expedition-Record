-- =====================================================
-- V2: Game tables - extends players and adds all game entities
-- =====================================================

ALTER TABLE players ADD COLUMN pos_x INT DEFAULT 0;
ALTER TABLE players ADD COLUMN pos_y INT DEFAULT 0;
ALTER TABLE players ADD COLUMN city_pos_x INT DEFAULT 0;
ALTER TABLE players ADD COLUMN city_pos_y INT DEFAULT 0;
ALTER TABLE players ADD COLUMN tax INT DEFAULT 30;
ALTER TABLE players ADD COLUMN morale INT DEFAULT 70;
ALTER TABLE players ADD COLUMN prestige INT DEFAULT 0;
ALTER TABLE players ADD COLUMN last_tick BIGINT DEFAULT 0;

CREATE TABLE IF NOT EXISTS resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    food INT DEFAULT 0,
    steel INT DEFAULT 0,
    oil INT DEFAULT 0,
    rare INT DEFAULT 0,
    gold INT DEFAULT 0,
    CONSTRAINT fk_resources_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_resources_player ON resources(player_id);

CREATE TABLE IF NOT EXISTS buildings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    level INT DEFAULT 0,
    CONSTRAINT fk_buildings_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_buildings_player ON buildings(player_id);

CREATE TABLE IF NOT EXISTS army_units (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    count INT DEFAULT 0,
    CONSTRAINT fk_army_units_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_army_units_player ON army_units(player_id);

CREATE TABLE IF NOT EXISTS fortifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    count INT DEFAULT 0,
    CONSTRAINT fk_fortifications_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_fortifications_player ON fortifications(player_id);

CREATE TABLE IF NOT EXISTS technologies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    level INT DEFAULT 0,
    CONSTRAINT fk_technologies_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_technologies_player ON technologies(player_id);

CREATE TABLE IF NOT EXISTS officers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    star INT DEFAULT 1,
    level INT DEFAULT 1,
    military INT DEFAULT 0,
    logistics INT DEFAULT 0,
    knowledge INT DEFAULT 0,
    loyalty INT DEFAULT 60,
    salary INT DEFAULT 0,
    role VARCHAR(50) DEFAULT 'idle',
    recruit_at BIGINT DEFAULT 0,
    reward_at BIGINT DEFAULT 0,
    skills TEXT,
    bio TEXT,
    CONSTRAINT fk_officers_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_officers_player ON officers(player_id);

CREATE TABLE IF NOT EXISTS constructions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    target_level INT NOT NULL,
    start_at BIGINT DEFAULT 0,
    finish_at BIGINT DEFAULT 0,
    CONSTRAINT fk_constructions_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_constructions_player ON constructions(player_id);

CREATE TABLE IF NOT EXISTS academy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    refresh_at BIGINT DEFAULT 0,
    officers TEXT,
    CONSTRAINT fk_academy_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_academy_player ON academy(player_id);

CREATE TABLE IF NOT EXISTS city_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'peace',
    war_target_id BIGINT,
    war_at BIGINT DEFAULT 0,
    war_end_at BIGINT DEFAULT 0,
    shield_until BIGINT DEFAULT 0,
    peace_until BIGINT DEFAULT 0,
    CONSTRAINT fk_city_state_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_city_state_player ON city_state(player_id);

CREATE TABLE IF NOT EXISTS world_map (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    size INT DEFAULT 200,
    scan_radius INT DEFAULT 3,
    pos_x INT DEFAULT 0,
    pos_y INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS npc_cities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    world_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    level INT DEFAULT 1,
    x INT NOT NULL,
    y INT NOT NULL,
    army TEXT,
    forts TEXT,
    resources TEXT,
    defeated BOOLEAN DEFAULT FALSE,
    scouted_by TEXT,
    CONSTRAINT fk_npc_cities_world FOREIGN KEY (world_id) REFERENCES world_map(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_npc_cities_world ON npc_cities(world_id);

CREATE TABLE IF NOT EXISTS player_cities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    world_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    owner_id BIGINT,
    level INT DEFAULT 1,
    x INT NOT NULL,
    y INT NOT NULL,
    army TEXT,
    forts TEXT,
    resources TEXT,
    prestige INT DEFAULT 0,
    war_at BIGINT DEFAULT 0,
    war_end_at BIGINT DEFAULT 0,
    scouted_by TEXT,
    CONSTRAINT fk_player_cities_world FOREIGN KEY (world_id) REFERENCES world_map(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_player_cities_world ON player_cities(world_id);
CREATE INDEX idx_player_cities_owner ON player_cities(owner_id);

CREATE TABLE IF NOT EXISTS bandits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    world_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    level INT DEFAULT 1,
    x INT NOT NULL,
    y INT NOT NULL,
    army TEXT,
    defeated BOOLEAN DEFAULT FALSE,
    CONSTRAINT fk_bandits_world FOREIGN KEY (world_id) REFERENCES world_map(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_bandits_world ON bandits(world_id);

CREATE TABLE IF NOT EXISTS wild_tiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    world_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    level INT DEFAULT 1,
    garrison TEXT,
    scouted BOOLEAN DEFAULT FALSE,
    occupied BOOLEAN DEFAULT FALSE,
    occupied_by BIGINT,
    total_res INT DEFAULT 0,
    mined INT DEFAULT 0,
    CONSTRAINT fk_wild_tiles_world FOREIGN KEY (world_id) REFERENCES world_map(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_wild_tiles_world ON wild_tiles(world_id);

CREATE TABLE IF NOT EXISTS marches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    target_kind VARCHAR(50),
    target_idx INT,
    target_id VARCHAR(100),
    target_name VARCHAR(100),
    target_x INT,
    target_y INT,
    from_x INT,
    from_y INT,
    distance INT DEFAULT 0,
    action VARCHAR(50),
    army TEXT,
    commander_id BIGINT,
    carry_res TEXT,
    start_at BIGINT DEFAULT 0,
    arrive_at BIGINT DEFAULT 0,
    returning BOOLEAN DEFAULT FALSE,
    gathering BOOLEAN DEFAULT FALSE,
    gather_end_at BIGINT DEFAULT 0,
    gather_amount INT DEFAULT 0,
    gather_res TEXT,
    CONSTRAINT fk_marches_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_marches_player ON marches(player_id);

CREATE TABLE IF NOT EXISTS scout_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    target_x INT,
    target_y INT,
    target_name VARCHAR(100),
    data TEXT,
    created_at BIGINT DEFAULT 0,
    CONSTRAINT fk_scout_reports_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_scout_reports_player ON scout_reports(player_id);

CREATE TABLE IF NOT EXISTS incoming_marches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_player_id BIGINT NOT NULL,
    from_name VARCHAR(100),
    from_x INT,
    from_y INT,
    army TEXT,
    arrive_at BIGINT DEFAULT 0,
    action VARCHAR(50),
    CONSTRAINT fk_incoming_marches_player FOREIGN KEY (target_player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_incoming_marches_player ON incoming_marches(target_player_id);
