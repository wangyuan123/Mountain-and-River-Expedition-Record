-- =====================================================
-- V13: Player account disable (账号注销软删除标记)
--  - disabled   : 0=正常, 1=已注销
--  - disabled_at: 注销时间戳 (毫秒)
-- =====================================================

ALTER TABLE players ADD COLUMN disabled INT DEFAULT 0;
ALTER TABLE players ADD COLUMN disabled_at BIGINT DEFAULT 0;
