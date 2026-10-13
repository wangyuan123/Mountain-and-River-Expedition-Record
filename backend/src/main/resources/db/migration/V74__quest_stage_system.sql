-- ============================================================================
-- V74: Quest stage progress and stage gift box claim status
-- ============================================================================

CREATE TABLE IF NOT EXISTS player_quest_stages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    stage_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'in_progress',
    completed_at BIGINT,
    claimed_at BIGINT,
    UNIQUE KEY uq_player_stage (player_id, stage_id),
    KEY idx_pqs_player_status (player_id, status),
    CONSTRAINT fk_pqs_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
