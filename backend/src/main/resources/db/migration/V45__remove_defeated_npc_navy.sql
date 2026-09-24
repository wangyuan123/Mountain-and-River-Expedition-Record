-- Defeated NPC cities are replaced immediately by the application; remove legacy tombstones.
DELETE FROM npc_cities WHERE defeated = TRUE;

-- NPC cities are land/air targets only, including records created by older saves.
UPDATE npc_cities
SET army = JSON_REMOVE(army, '$.destroyer', '$.sub', '$.battleship', '$.carrier')
WHERE JSON_VALID(army);

UPDATE bandits
SET army = JSON_REMOVE(army, '$.destroyer', '$.sub', '$.battleship', '$.carrier')
WHERE JSON_VALID(army);
