-- 军校刷新由 players.academy_refresh_* 维护；移除不再读取的城市级副本。
ALTER TABLE academy DROP COLUMN refresh_at;

-- 仅移除 city_state 的旧宣战字段；保留玩家/城市战争时间和现有保护期结算。
ALTER TABLE city_state DROP COLUMN war_at;
ALTER TABLE city_state DROP COLUMN war_target_id;

-- 行军按 target_id 定位目标，军官装备以 officer_equipment 明细为准。
ALTER TABLE marches DROP COLUMN target_idx;
ALTER TABLE officers DROP COLUMN equipment_count;
