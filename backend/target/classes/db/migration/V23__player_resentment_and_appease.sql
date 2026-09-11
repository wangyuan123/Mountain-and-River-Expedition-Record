-- =====================================================
-- V23: Add player resentment and last appease timestamp
-- =====================================================

ALTER TABLE players ADD COLUMN resentment INT DEFAULT 0;
ALTER TABLE players ADD COLUMN last_appease_at BIGINT DEFAULT 0;
