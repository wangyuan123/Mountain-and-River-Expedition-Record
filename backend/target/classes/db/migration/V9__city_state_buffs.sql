-- ============================================================================
-- V9: city_state 增加功能类道具效果字段 (行军令 / 反侦察符)
--   1) march_boost_until: 行军令生效截止时间 (毫秒), 0 表示未生效
--   2) cloak_until:       反侦察符生效截止时间 (毫秒), 0 表示未生效
-- 幂等: 用 information_schema 检测列是否存在, 兼容 MySQL 8+/9.x
-- ============================================================================

SET @has_mb := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'city_state' AND column_name = 'march_boost_until'
);
SET @sql_text := IF(@has_mb = 0,
  'ALTER TABLE city_state ADD COLUMN march_boost_until BIGINT DEFAULT 0 AFTER peace_until',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_cl := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'city_state' AND column_name = 'cloak_until'
);
SET @sql_text := IF(@has_cl = 0,
  'ALTER TABLE city_state ADD COLUMN cloak_until BIGINT DEFAULT 0 AFTER march_boost_until',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;
