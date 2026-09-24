-- Only scale surviving troops in existing undefeated camps; never revive defeated camps.
-- Rewards are level-based in WorldConfig and require no stored-resource migration.
UPDATE bandits SET army = JSON_SET(army, '$.infantry', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.infantry')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.infantry');
UPDATE bandits SET army = JSON_SET(army, '$.motor', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.motor')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE bandits SET army = JSON_SET(army, '$.armored', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.armored')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.armored');
UPDATE bandits SET army = JSON_SET(army, '$.ltank', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.ltank')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.ltank');
UPDATE bandits SET army = JSON_SET(army, '$.htank', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.htank')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.htank');
UPDATE bandits SET army = JSON_SET(army, '$.assault', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.assault')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.assault');
UPDATE bandits SET army = JSON_SET(army, '$.rocket', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.rocket')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.rocket');
UPDATE bandits SET army = JSON_SET(army, '$.fighter', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.fighter')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.fighter');
UPDATE bandits SET army = JSON_SET(army, '$.bomber', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.bomber')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.bomber');
UPDATE bandits SET army = JSON_SET(army, '$.sub', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.sub')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.sub');
UPDATE bandits SET army = JSON_SET(army, '$.battleship', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.battleship')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.battleship');
UPDATE bandits SET army = JSON_SET(army, '$.carrier', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.carrier')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.carrier');

-- Older worlds can still contain NPC cities even though new worlds no longer generate them.
UPDATE npc_cities SET army = JSON_SET(army, '$.infantry', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.infantry')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.infantry');
UPDATE npc_cities SET army = JSON_SET(army, '$.motor', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.motor')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.motor');
UPDATE npc_cities SET army = JSON_SET(army, '$.armored', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.armored')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.armored');
UPDATE npc_cities SET army = JSON_SET(army, '$.ltank', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.ltank')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.ltank');
UPDATE npc_cities SET army = JSON_SET(army, '$.htank', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.htank')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.htank');
UPDATE npc_cities SET army = JSON_SET(army, '$.assault', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.assault')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.assault');
UPDATE npc_cities SET army = JSON_SET(army, '$.rocket', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.rocket')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.rocket');
UPDATE npc_cities SET army = JSON_SET(army, '$.fighter', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.fighter')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.fighter');
UPDATE npc_cities SET army = JSON_SET(army, '$.bomber', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.bomber')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.bomber');
UPDATE npc_cities SET army = JSON_SET(army, '$.sub', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.sub')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.sub');
UPDATE npc_cities SET army = JSON_SET(army, '$.battleship', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.battleship')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.battleship');
UPDATE npc_cities SET army = JSON_SET(army, '$.carrier', CAST(JSON_UNQUOTE(JSON_EXTRACT(army, '$.carrier')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(army) AND JSON_CONTAINS_PATH(army, 'one', '$.carrier');

-- Do not scale exp: only tangible supplies are plundered after combat.
UPDATE npc_cities SET resources = JSON_SET(resources, '$.food', CAST(JSON_UNQUOTE(JSON_EXTRACT(resources, '$.food')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(resources) AND JSON_CONTAINS_PATH(resources, 'one', '$.food');
UPDATE npc_cities SET resources = JSON_SET(resources, '$.steel', CAST(JSON_UNQUOTE(JSON_EXTRACT(resources, '$.steel')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(resources) AND JSON_CONTAINS_PATH(resources, 'one', '$.steel');
UPDATE npc_cities SET resources = JSON_SET(resources, '$.oil', CAST(JSON_UNQUOTE(JSON_EXTRACT(resources, '$.oil')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(resources) AND JSON_CONTAINS_PATH(resources, 'one', '$.oil');
UPDATE npc_cities SET resources = JSON_SET(resources, '$.rare', CAST(JSON_UNQUOTE(JSON_EXTRACT(resources, '$.rare')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(resources) AND JSON_CONTAINS_PATH(resources, 'one', '$.rare');
UPDATE npc_cities SET resources = JSON_SET(resources, '$.gold', CAST(JSON_UNQUOTE(JSON_EXTRACT(resources, '$.gold')) AS UNSIGNED) * 3)
WHERE defeated = FALSE AND JSON_VALID(resources) AND JSON_CONTAINS_PATH(resources, 'one', '$.gold');
