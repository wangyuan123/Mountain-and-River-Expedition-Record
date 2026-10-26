-- 采集统一为手动：保留计时、部队、路线与货物，只取消未执行的自动收尾指令。
ALTER TABLE marches ALTER COLUMN gather_mode SET DEFAULT 'manual';
UPDATE marches SET gather_mode = 'manual' WHERE target_kind = 'wild_gather' OR action = 'gather';
UPDATE marches SET gather_after_action = 'retain',
    gather_after_action_reason = CASE WHEN gather_after_action_result = 'pending'
        THEN '采集已改为手动，请手动收获、下令回城及放弃野地' ELSE gather_after_action_reason END,
    gather_after_action_result = CASE WHEN gather_after_action_result = 'pending'
        THEN 'cancelled' ELSE gather_after_action_result END
WHERE target_kind = 'wild_gather' AND (returning = FALSE OR returning IS NULL);
UPDATE wild_tiles SET gather_mode = 'manual';
