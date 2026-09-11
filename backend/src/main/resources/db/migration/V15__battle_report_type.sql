-- =====================================================
-- V15: Battle report type (战报类型: scout=侦查 / battle=战斗)
-- =====================================================

ALTER TABLE scout_reports ADD COLUMN type VARCHAR(20) DEFAULT 'scout';
CREATE INDEX idx_scout_reports_type ON scout_reports(type);
