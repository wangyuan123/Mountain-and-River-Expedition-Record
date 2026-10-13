-- NPC 城市/要塞取消；陆地流寇统一显示为“寇”。
-- 有旧行军/战斗引用的记录暂留，结算后由定时人口维护清理；地图不会展示这些旧城。
DELETE FROM npc_cities
WHERE NOT EXISTS (
    SELECT 1 FROM marches m
    WHERE m.target_kind = 'npc' AND m.target_id = CONVERT(npc_cities.id USING utf8mb4) COLLATE utf8mb4_unicode_ci
)
AND NOT EXISTS (
    SELECT 1 FROM battle_sessions b
    WHERE b.target_kind = 'npc' AND b.target_id = CONVERT(npc_cities.id USING utf8mb4) COLLATE utf8mb4_unicode_ci
);

UPDATE bandits
SET name = CONCAT('寇 Lv.', COALESCE(level, 1))
WHERE name LIKE '日寇前哨%'
   OR name LIKE '日寇营地%'
   OR name LIKE '日寇炮楼%'
   OR name LIKE '日寇据点%'
   OR name LIKE '日寇补给站%'
   OR name LIKE '雇佣兵营%'
   OR name LIKE '武装走私队%'
   OR name LIKE '雇佣兵基地%';
