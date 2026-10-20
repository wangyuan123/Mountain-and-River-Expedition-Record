-- 新付费订单记录解锁版本与完整条件快照；历史订单保持NULL并按原规则完成。
ALTER TABLE army_production_queue ADD COLUMN unlock_rule_version VARCHAR(64) NULL;
ALTER TABLE army_production_queue ADD COLUMN unlock_requirements TEXT NULL;
ALTER TABLE unit_equipment_orders ADD COLUMN unlock_rule_version VARCHAR(64) NULL;
ALTER TABLE unit_equipment_orders ADD COLUMN unlock_requirements TEXT NULL;

-- q3_2的章节由目录定义，不改任务ID、完成事实及领奖标记。
-- 第三章移除海军任务后重新确认完成；第五章既有完成/领奖记录不撤销。
UPDATE player_quest_stages SET status = 'completed',
    completed_at = COALESCE(completed_at, UNIX_TIMESTAMP() * 1000.0)
WHERE stage_id = 'stage3' AND status = 'in_progress'
  AND player_id IN (SELECT q.player_id FROM player_quests q
       WHERE q.quest_id IN ('q3_1','q3_3','q3_4','q3_5','q3_6','q3_7','q3_8')
       AND q.status IN ('completed','claimed') GROUP BY q.player_id HAVING COUNT(DISTINCT q.quest_id) = 7);
