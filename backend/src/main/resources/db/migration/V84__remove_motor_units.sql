-- 摩托兵已从正式编制中移除，清理玩家、战俘、队列和历史战场快照中的残留数量。
DELETE FROM army_production_queue WHERE unit_type = 'motor';
-- V30 已删除 player_cities.army，玩家所有城市的兵力统一从 army_units 清理。
DELETE FROM army_units WHERE type = 'motor';
DELETE FROM wounded_units WHERE type = 'motor';
DELETE FROM prisoner_units WHERE type = 'motor';
DELETE FROM prisoner_operations WHERE type = 'motor';

UPDATE players
SET sortie_army = JSON_REMOVE(sortie_army, '$.motor')
WHERE JSON_VALID(sortie_army) AND JSON_CONTAINS_PATH(sortie_army, 'one', '$.motor');

UPDATE npc_cities
SET army = JSON_REMOVE(army, '$.motor')
WHERE JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE bandits
SET army = JSON_REMOVE(army, '$.motor')
WHERE JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE wild_tiles
SET garrison = JSON_REMOVE(garrison, '$.motor')
WHERE JSON_VALID(garrison) AND JSON_CONTAINS_PATH(garrison, 'one', '$.motor');
UPDATE marches
SET army = JSON_REMOVE(army, '$.motor')
WHERE JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE incoming_marches
SET army = JSON_REMOVE(army, '$.motor')
WHERE JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE battle_sessions
SET attacker_army = CASE WHEN JSON_VALID(attacker_army) THEN JSON_REMOVE(attacker_army, '$.motor') ELSE attacker_army END,
    defender_army = CASE WHEN JSON_VALID(defender_army) THEN JSON_REMOVE(defender_army, '$.motor') ELSE defender_army END,
    initial_attacker = CASE WHEN JSON_VALID(initial_attacker) THEN JSON_REMOVE(initial_attacker, '$.motor') ELSE initial_attacker END,
    initial_defender = CASE WHEN JSON_VALID(initial_defender) THEN JSON_REMOVE(initial_defender, '$.motor') ELSE initial_defender END,
    attacker_positions = CASE WHEN JSON_VALID(attacker_positions) THEN JSON_REMOVE(attacker_positions, '$.motor') ELSE attacker_positions END,
    defender_positions = CASE WHEN JSON_VALID(defender_positions) THEN JSON_REMOVE(defender_positions, '$.motor') ELSE defender_positions END
WHERE JSON_VALID(attacker_army) OR JSON_VALID(defender_army)
   OR JSON_VALID(initial_attacker) OR JSON_VALID(initial_defender)
   OR JSON_VALID(attacker_positions) OR JSON_VALID(defender_positions);
