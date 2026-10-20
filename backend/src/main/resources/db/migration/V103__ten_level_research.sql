-- 五级制四项科技一次映射至偶数等级；不改历史行军、战报与已制造装备。
UPDATE technologies SET level = 2 * LEAST(5, GREATEST(0, COALESCE(level, 0)))
WHERE type IN ('unit_refit', 'log_warehouse', 'recon_level', 'recon_radar');
-- 旧付费任务允许跨奇数级完成；费用、工期、完成时间、暂停与前置快照均保留。
UPDATE tech_research_queue SET target_level = 2 * LEAST(5, GREATEST(0, target_level))
WHERE tech_type IN ('unit_refit', 'log_warehouse', 'recon_level', 'recon_radar');
