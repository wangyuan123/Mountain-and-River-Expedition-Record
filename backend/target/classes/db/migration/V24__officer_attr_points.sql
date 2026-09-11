-- =====================================================
-- V24: Add officer attr_points for manual attribute allocation
-- =====================================================

ALTER TABLE officers ADD COLUMN attr_points INT NOT NULL DEFAULT 0;
