-- =====================================================
-- V38: Add defense attribute to officers and officer_equipment
-- =====================================================

ALTER TABLE officers ADD COLUMN defense INT NOT NULL DEFAULT 0;

ALTER TABLE officer_equipment ADD COLUMN defense_bonus INT NOT NULL DEFAULT 0;

-- Update existing officers: if defense is 0, give reasonable baseline
UPDATE officers SET defense = military WHERE defense = 0 AND military > 0;
UPDATE officers SET defense = 30 + star * 12 WHERE defense = 0;
