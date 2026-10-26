ALTER TABLE wild_tiles ADD COLUMN occupation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE marches ADD COLUMN gather_after_action VARCHAR(10) NOT NULL DEFAULT 'retain';
ALTER TABLE marches ADD COLUMN gather_occupation_version BIGINT NULL;
ALTER TABLE marches ADD COLUMN gather_owner_city_slot INT NULL;
ALTER TABLE marches ADD COLUMN gather_resource_settled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marches ADD COLUMN gather_after_action_result VARCHAR(12) NULL;
ALTER TABLE marches ADD COLUMN gather_after_action_reason VARCHAR(255) NULL;
CREATE INDEX idx_march_wild_target ON marches(target_kind, target_id);

-- 无法可靠对应旧归属的返程任务只保留既有货物，不能追溯扣减当前野地。
UPDATE marches SET gather_resource_settled = TRUE
WHERE target_kind = 'wild_gather' AND returning = TRUE;

-- 升级时冻结仍在原归属中的旧任务；-1 表示原归属失效，禁止之后绑定新占领。
UPDATE marches SET gather_occupation_version = COALESCE(
    (SELECT w.occupation_version FROM wild_tiles w WHERE CONCAT('', w.id) = marches.target_id
        AND w.occupied = TRUE AND w.occupied_by = marches.player_id), -1),
    gather_owner_city_slot =
    (SELECT w.occupied_city_slot FROM wild_tiles w WHERE CONCAT('', w.id) = marches.target_id
        AND w.occupied = TRUE AND w.occupied_by = marches.player_id)
WHERE target_kind = 'wild_gather' AND (returning = FALSE OR returning IS NULL);
