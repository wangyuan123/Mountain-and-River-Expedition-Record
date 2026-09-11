-- =====================================================
-- V14: Scout report read state (战报已读标记)
--  - read_at: 首次被玩家阅读的时间戳 (毫秒), 0 表示未读
-- =====================================================

ALTER TABLE scout_reports ADD COLUMN read_at BIGINT DEFAULT 0;
