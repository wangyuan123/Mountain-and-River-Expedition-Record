-- 退款和清理已在 V144 的事务中完成；保留在途负重科技与协防来源的原有快照。
DROP TABLE IF EXISTS unit_equipment_orders;
DROP TABLE IF EXISTS unit_loadouts;
ALTER TABLE marches RENAME COLUMN unit_equipment_snapshot TO action_snapshot;
ALTER TABLE battle_sessions RENAME COLUMN defender_equipment_sources TO defender_support_sources;
