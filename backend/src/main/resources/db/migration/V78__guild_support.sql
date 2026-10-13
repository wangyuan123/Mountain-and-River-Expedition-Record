ALTER TABLE marches ADD COLUMN stationed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marches ADD COLUMN support_owner_id BIGINT NULL;
CREATE INDEX idx_march_stationed_city ON marches (target_kind, target_id, stationed);
ALTER TABLE battle_sessions ADD COLUMN defender_support LONGTEXT NULL;
