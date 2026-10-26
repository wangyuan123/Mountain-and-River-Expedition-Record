ALTER TABLE battle_sessions ADD COLUMN joint_defense LONGTEXT NULL;
ALTER TABLE player_cities ADD COLUMN accept_garrison BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE player_cities ADD COLUMN garrison_food_remainder DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE player_cities ADD COLUMN garrison_food_unmet DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE player_cities ADD COLUMN garrison_supply_warned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marches ADD COLUMN garrison_food_at BIGINT NULL;
ALTER TABLE marches ADD COLUMN displaced BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE player_cities ADD COLUMN garrison_food_at BIGINT NULL;

UPDATE player_cities SET garrison_food_at = (SELECT game_time FROM game_clock WHERE singleton_id = 1)
WHERE EXISTS (SELECT 1 FROM marches WHERE stationed = TRUE AND target_kind = 'player' AND marches.target_id = CAST(player_cities.id AS CHAR(30)));
UPDATE marches SET garrison_food_at = (SELECT game_time FROM game_clock WHERE singleton_id = 1)
WHERE stationed = TRUE AND target_kind = 'player';
