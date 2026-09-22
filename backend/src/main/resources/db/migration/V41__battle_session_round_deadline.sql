ALTER TABLE battle_sessions
    ADD COLUMN round_deadline_at BIGINT NOT NULL DEFAULT 0 AFTER round_no;
