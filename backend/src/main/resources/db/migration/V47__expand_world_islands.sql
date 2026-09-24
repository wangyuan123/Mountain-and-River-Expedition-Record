ALTER TABLE world_map
    ADD COLUMN terrain_version INT NOT NULL DEFAULT 1,
    ADD COLUMN island_content_version INT NOT NULL DEFAULT 0;
