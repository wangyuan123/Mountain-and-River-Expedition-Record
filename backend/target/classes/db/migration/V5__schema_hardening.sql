-- ============================================================================
-- V5: Schema hardening (全脚本幂等, MySQL 9.x 兼容)
--   1) Cleanup orphan worlds (from previous genWorld(null) bug)
--   2) Add unique constraint on (player_id, type) in army_units
--   3) Add unique constraint on (player_id, type) in fortifications
--   4) Add unique constraint on (player, type, is_slot0) in buildings
--   5) Enforce single WorldMap (only one row in world_map)
--   6) Add player_items table for skillBook / expBook / etc. inventory
--   7) Add officers.exp column
--
-- 设计要点: 因为 MySQL DDL 不参与事务, 多次重跑必须用 IF NOT EXISTS 或
-- information_schema 检测后决定是否执行; 否则 ALTER TABLE 在已存在时会
-- 报 1060 (列已存在) 或 1061 (索引已存在) 导致整个 V5 标 failed。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) Orphan world cleanup
-- ---------------------------------------------------------------------------
SELECT MIN(id) INTO @keep_world_id FROM world_map;
DELETE FROM bandits       WHERE world_id <> @keep_world_id;
DELETE FROM npc_cities    WHERE world_id <> @keep_world_id;
DELETE FROM player_cities WHERE world_id <> @keep_world_id;
DELETE FROM wild_tiles    WHERE world_id <> @keep_world_id;
DELETE FROM world_map     WHERE id        <> @keep_world_id;

-- ---------------------------------------------------------------------------
-- 2) buildings.slot + is_slot0 (generated) - 用于多槽位建筑的 partial uniq
-- ---------------------------------------------------------------------------
SET @has_slot := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'buildings' AND column_name = 'slot'
);
SET @sql_text := IF(@has_slot = 0,
  'ALTER TABLE buildings ADD COLUMN slot INT NOT NULL DEFAULT 0 AFTER level',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Collapse duplicates: keep lowest id per (player_id, type, slot=0)
DELETE b1 FROM buildings b1
INNER JOIN buildings b2
  ON b1.player_id = b2.player_id
 AND b1.type = b2.type
 AND b1.id > b2.id
 AND b1.slot = 0 AND b2.slot = 0;

-- For multi-slot buildings, assign slots 0..N-1 by id order.
-- MySQL 限制: 不能 UPDATE 一张表, 其子查询又引用同一张表。
-- 解决方案: 派生表 + 多表 UPDATE 语法 (MySQL 8+ / 9.x 兼容)。
UPDATE buildings b
INNER JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY player_id, type ORDER BY id) - 1 AS new_slot
  FROM buildings
  WHERE slot = 0
) sub ON b.id = sub.id
SET b.slot = sub.new_slot
WHERE b.slot = 0
  AND b.type IN (
    SELECT m.type FROM (
      SELECT type, player_id, COUNT(*) AS cnt
      FROM buildings
      WHERE slot = 0
      GROUP BY player_id, type
      HAVING cnt > 1
    ) m
  );

-- ---------------------------------------------------------------------------
-- 3) army_units UNIQUE (player_id, type)
-- ---------------------------------------------------------------------------
-- 重复行去重 (id 最小者保留) 必须在 ADD UNIQUE 之前, 否则 ALTER 会失败
DELETE a1 FROM army_units a1
INNER JOIN army_units a2
  ON a1.player_id = a2.player_id AND a1.type = a2.type AND a1.id > a2.id;

SET @has_uq_army := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'army_units' AND index_name = 'uq_army_player_type'
);
SET @sql_text := IF(@has_uq_army = 0,
  'ALTER TABLE army_units ADD UNIQUE KEY uq_army_player_type (player_id, type)',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4) fortifications UNIQUE (player_id, type)
-- ---------------------------------------------------------------------------
DELETE f1 FROM fortifications f1
INNER JOIN fortifications f2
  ON f1.player_id = f2.player_id AND f1.type = f2.type AND f1.id > f2.id;

SET @has_uq_fort := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'fortifications' AND index_name = 'uq_fort_player_type'
);
SET @sql_text := IF(@has_uq_fort = 0,
  'ALTER TABLE fortifications ADD UNIQUE KEY uq_fort_player_type (player_id, type)',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5) buildings.is_slot0 generated col + UNIQUE (player, type, is_slot0)
-- ---------------------------------------------------------------------------
SET @slot0_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'buildings' AND column_name = 'is_slot0'
);
SET @sql_text := IF(@slot0_exists = 0,
  'ALTER TABLE buildings ADD COLUMN is_slot0 TINYINT(1) GENERATED ALWAYS AS (IF(slot=0,1,NULL)) VIRTUAL',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_uq_bld := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'buildings' AND index_name = 'uq_building_player_type_slot0'
);
SET @sql_text := IF(@has_uq_bld = 0,
  'ALTER TABLE buildings ADD UNIQUE KEY uq_building_player_type_slot0 (player_id, type, is_slot0)',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 6) world_map: 最多 1 行 (使用派生表绕过 MySQL 不能引用目标表的限制)
-- ---------------------------------------------------------------------------
DELETE FROM world_map
WHERE id NOT IN (
  SELECT * FROM (SELECT MIN(id) FROM world_map) AS keep
);

-- ---------------------------------------------------------------------------
-- 7) player_items 表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS player_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    item_key VARCHAR(50) NOT NULL,
    count INT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uq_player_item (player_id, item_key),
    CONSTRAINT fk_player_items_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed: 已有玩家获得初始物品。
-- 注意: MySQL 9.x 在 `INSERT...SELECT...CROSS JOIN ... ON DUPLICATE KEY UPDATE`
-- 这种组合上解析有 bug (即使改用 CTE 也报错), 改用 UNION ALL 串接。
INSERT INTO player_items (player_id, item_key, count, updated_at)
SELECT p.id, 'skillBook', 3, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'expBook',   3, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'loyaltyBox',1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'expBookAdv',1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'renameCard',1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'goldBox',   1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'resBox',    1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'speedUp',   1, UNIX_TIMESTAMP() * 1000 FROM players p UNION ALL
SELECT p.id, 'shield',    0, UNIX_TIMESTAMP() * 1000 FROM players p
ON DUPLICATE KEY UPDATE count = count;

-- ---------------------------------------------------------------------------
-- 8) officers.exp
-- ---------------------------------------------------------------------------
SET @has_exp := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'officers' AND column_name = 'exp'
);
SET @sql_text := IF(@has_exp = 0,
  'ALTER TABLE officers ADD COLUMN exp BIGINT NOT NULL DEFAULT 0 AFTER salary',
  'DO 0');
PREPARE stmt FROM @sql_text; EXECUTE stmt; DEALLOCATE PREPARE stmt;
