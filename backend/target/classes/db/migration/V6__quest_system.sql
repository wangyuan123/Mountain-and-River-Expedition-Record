-- ============================================================================
-- V6: Main quest + newbie guide system
--   1) player_quests: per-player quest state (status / progress / claim time)
--   2) player_guides: per-player guide step (the floating tutorial pointer)
-- ============================================================================

CREATE TABLE IF NOT EXISTS player_quests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    quest_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'in_progress',  -- locked / in_progress / completed / claimed
    progress INT NOT NULL DEFAULT 0,
    target_value INT NOT NULL DEFAULT 1,
    completed_at BIGINT,
    claimed_at BIGINT,
    UNIQUE KEY uq_player_quest (player_id, quest_id),
    KEY idx_quest_status (player_id, status),
    CONSTRAINT fk_player_quests_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_guide (
    player_id BIGINT NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',  -- active / skipped / done
    completed_at BIGINT,
    PRIMARY KEY (player_id, step_id),
    CONSTRAINT fk_player_guide_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
