UPDATE battle_sessions
SET round_deadline_at = 0
WHERE round_deadline_at IS NULL;

ALTER TABLE battle_sessions
    MODIFY COLUMN round_deadline_at BIGINT NOT NULL DEFAULT 0;
