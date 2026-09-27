-- =====================================================
-- V56: Add commander_name to bandits and npc_cities
-- =====================================================

ALTER TABLE bandits ADD COLUMN commander_name VARCHAR(100);
ALTER TABLE npc_cities ADD COLUMN commander_name VARCHAR(100);

-- 回填现存未被击败的流寇和 NPC 城市指挥官
UPDATE bandits SET commander_name = '牟田口廉也' WHERE commander_name IS NULL AND (level >= 28 OR level IS NULL);
UPDATE bandits SET commander_name = '木村兵太郎' WHERE commander_name IS NULL AND level >= 25;
UPDATE bandits SET commander_name = '本多政材' WHERE commander_name IS NULL AND level >= 20;
UPDATE bandits SET commander_name = '饭田祥二郎' WHERE commander_name IS NULL AND level >= 15;
UPDATE bandits SET commander_name = '田中新一' WHERE commander_name IS NULL AND level >= 10;
UPDATE bandits SET commander_name = '渡边正夫' WHERE commander_name IS NULL AND level >= 5;
UPDATE bandits SET commander_name = '辻政信' WHERE commander_name IS NULL;

UPDATE npc_cities SET commander_name = '木村兵太郎' WHERE commander_name IS NULL AND (level >= 7 OR level IS NULL);
UPDATE npc_cities SET commander_name = '本多政材' WHERE commander_name IS NULL AND level >= 5;
UPDATE npc_cities SET commander_name = '饭田祥二郎' WHERE commander_name IS NULL;
