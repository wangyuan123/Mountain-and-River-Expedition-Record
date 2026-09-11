-- =====================================================
-- V3: Add slot column to constructions table
-- Supports multi-slot building construction tracking
-- =====================================================

ALTER TABLE constructions ADD COLUMN slot INT;
