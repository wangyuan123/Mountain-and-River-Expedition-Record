-- Existing marches retain their clocks; base duration is captured before the first acceleration.
ALTER TABLE marches ADD COLUMN base_duration_ms BIGINT NULL;
ALTER TABLE marches ADD COLUMN outbound_accelerated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marches ADD COLUMN return_accelerated BOOLEAN NOT NULL DEFAULT FALSE;
