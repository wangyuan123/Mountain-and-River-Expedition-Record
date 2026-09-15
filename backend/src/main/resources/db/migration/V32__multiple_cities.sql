-- Slot zero retains existing main-city data; only new cities use additional slots.
ALTER TABLE players ADD COLUMN active_city_id BIGINT NULL;
ALTER TABLE player_cities
    ADD COLUMN city_slot INT NULL,
    ADD COLUMN ready_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tax INT NOT NULL DEFAULT 30,
    ADD COLUMN morale INT NOT NULL DEFAULT 70,
    ADD COLUMN resentment INT NOT NULL DEFAULT 0,
    ADD COLUMN last_appease_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN civilian_population INT NOT NULL DEFAULT 50,
    ADD COLUMN population_growth_remainder DOUBLE NOT NULL DEFAULT 0,
    ADD COLUMN last_tick BIGINT NOT NULL DEFAULT 0;
-- Only one verified coordinate match per player is the main city. Legacy map entries stay untagged.
UPDATE player_cities c JOIN (
    SELECT p.id AS owner_id, MIN(pc.id) AS city_id FROM players p
    JOIN player_cities pc ON pc.owner_id = p.id AND pc.x = p.city_pos_x AND pc.y = p.city_pos_y
    GROUP BY p.id
) main_city ON c.id = main_city.city_id SET c.city_slot = 0;
CREATE UNIQUE INDEX uq_player_city_slot ON player_cities(owner_id, city_slot);
ALTER TABLE resources ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_resources_city ON resources(player_id, city_slot);
ALTER TABLE buildings ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_buildings_city ON buildings(player_id, city_slot);
ALTER TABLE army_units ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_army_units_city ON army_units(player_id, city_slot);
ALTER TABLE constructions ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_constructions_city ON constructions(player_id, city_slot);
ALTER TABLE marches ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_marches_city ON marches(player_id, city_slot);
ALTER TABLE officers ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_officers_city ON officers(player_id, city_slot);
ALTER TABLE army_production_queue ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_army_production_queue_city ON army_production_queue(player_id, city_slot);
ALTER TABLE fortifications ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_fortifications_city ON fortifications(player_id, city_slot);
ALTER TABLE city_state ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_city_state_city ON city_state(player_id, city_slot);
ALTER TABLE academy ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_academy_city ON academy(player_id, city_slot);
ALTER TABLE wounded_units ADD COLUMN city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_wounded_units_city ON wounded_units(player_id, city_slot);
-- Keep the player-prefix index needed by existing foreign keys while widening city uniqueness.
ALTER TABLE army_units DROP INDEX uq_army_player_type, ADD UNIQUE INDEX uq_army_player_type (player_id, city_slot, type);
ALTER TABLE fortifications DROP INDEX uq_fort_player_type, ADD UNIQUE INDEX uq_fort_player_type (player_id, city_slot, type);
ALTER TABLE buildings DROP INDEX uq_building_player_type_slot0, ADD UNIQUE INDEX uq_building_player_type_slot0 (player_id, city_slot, type, is_slot0);
