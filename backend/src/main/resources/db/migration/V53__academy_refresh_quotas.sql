-- 按账号持久化刷新额度；旧版每次刷新后的单次冷却不带入新规则。
ALTER TABLE players ADD COLUMN academy_refresh_round_count INT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN academy_refresh_daily_count INT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN academy_refresh_day DATE;
ALTER TABLE players ADD COLUMN academy_refresh_at BIGINT NOT NULL DEFAULT 0;
UPDATE academy SET refresh_at = 0;
