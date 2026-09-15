-- Keep all existing cities and marches; terrain is generated once with asset protection.
ALTER TABLE world_map ADD COLUMN terrain_data LONGTEXT NULL;
ALTER TABLE player_cities ADD COLUMN legacy_naval BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE player_cities SET legacy_naval = TRUE WHERE owner_id IS NOT NULL;
ALTER TABLE marches ADD COLUMN route_data LONGTEXT NULL;
ALTER TABLE marches ADD COLUMN route_mode VARCHAR(30) NULL;
