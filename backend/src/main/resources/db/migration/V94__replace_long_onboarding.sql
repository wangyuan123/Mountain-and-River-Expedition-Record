-- 开发阶段直接移除十五步引导与更早训练营的数据，新流程使用 ob3_，不迁移旧进度。
DELETE FROM player_guide WHERE LEFT(step_id, 4) = 'ob2_' OR LEFT(step_id, 2) = 'g_';
