ALTER TABLE wild_tiles ADD COLUMN depleted_at BIGINT NULL;

-- 旧存档中无主且已耗尽的野地从迁移时开始冷却，避免继续显示 0 资源空壳。
UPDATE wild_tiles SET depleted_at = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE (occupied = FALSE OR occupied IS NULL) AND total_res > 0 AND mined >= total_res;
