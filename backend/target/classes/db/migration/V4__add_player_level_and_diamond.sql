-- =====================================================
-- V4: Add player level/vip and resource diamond
-- =====================================================

ALTER TABLE players ADD COLUMN level INT DEFAULT 1;
ALTER TABLE players ADD COLUMN vip_level INT DEFAULT 0;

ALTER TABLE resources ADD COLUMN diamond INT DEFAULT 0;
