-- 野地归属按城市独立记录；旧领地默认归主城，不改变玩家归属、驻军和采集任务。
ALTER TABLE wild_tiles ADD COLUMN occupied_city_slot INT NOT NULL DEFAULT 0;
CREATE INDEX idx_wild_owner_city ON wild_tiles (occupied_by, occupied_city_slot, occupied);
