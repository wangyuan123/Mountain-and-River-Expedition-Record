ALTER TABLE wild_tiles ADD COLUMN gather_mode VARCHAR(10) NOT NULL DEFAULT 'manual';
ALTER TABLE wild_tiles ADD COLUMN gather_city_slot INT NOT NULL DEFAULT 0;
ALTER TABLE wild_tiles ADD COLUMN gather_harvested INT NULL;
ALTER TABLE wild_tiles ADD COLUMN garrison_routes LONGTEXT NULL;
CREATE INDEX idx_wild_auto_gather ON wild_tiles(occupied_by, gather_city_slot, gathering, gather_mode);
