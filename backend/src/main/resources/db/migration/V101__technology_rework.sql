-- 一次性迁移等级；科研行、已付费用、时间与前置快照保持原值，不拆分付费队列。
CREATE TEMPORARY TABLE technology_rework_targets (player_id BIGINT NOT NULL, type VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL, level INT NOT NULL) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO technology_rework_targets SELECT player_id, 'prod_food', MAX(LEAST(10, GREATEST(0, COALESCE(level,0)))) FROM technologies WHERE type = 'log_production' GROUP BY player_id;
INSERT INTO technology_rework_targets SELECT player_id, 'prod_steel', MAX(LEAST(10, GREATEST(0, COALESCE(level,0)))) FROM technologies WHERE type = 'log_production' GROUP BY player_id;
INSERT INTO technology_rework_targets SELECT player_id, 'prod_oil', MAX(LEAST(10, GREATEST(0, COALESCE(level,0)))) FROM technologies WHERE type = 'log_production' GROUP BY player_id;
INSERT INTO technology_rework_targets SELECT player_id, 'prod_rare', MAX(LEAST(10, GREATEST(0, COALESCE(level,0)))) FROM technologies WHERE type = 'log_production' GROUP BY player_id;
INSERT INTO technology_rework_targets SELECT player_id, 'log_load', MAX(2 * LEAST(5, GREATEST(0, COALESCE(level,0)))) FROM technologies WHERE type = 'inf_load' GROUP BY player_id;
UPDATE technologies SET level = GREATEST(COALESCE(level,0), COALESCE((SELECT MAX(r.level) FROM technology_rework_targets r WHERE r.player_id = technologies.player_id AND r.type = technologies.type),0)) WHERE type IN ('prod_food','prod_steel','prod_oil','prod_rare','log_load');
INSERT INTO technologies(player_id,type,level,version) SELECT r.player_id,r.type,r.level,0 FROM technology_rework_targets r WHERE NOT EXISTS (SELECT 1 FROM technologies t WHERE t.player_id=r.player_id AND t.type=r.type);
DELETE FROM technologies WHERE type IN ('log_production','inf_load');
UPDATE technologies SET level = CASE WHEN COALESCE(level,0) <= 0 THEN 0 ELSE 2 * LEAST(3,level) - 1 END WHERE type IN ('recon_radar','unit_refit');
UPDATE tech_research_queue SET target_level = 2 * LEAST(5,GREATEST(0,target_level)), tech_type = 'log_load' WHERE tech_type = 'inf_load';
UPDATE tech_research_queue SET target_level = CASE WHEN target_level <= 0 THEN 0 ELSE 2 * LEAST(3,target_level) - 1 END WHERE tech_type IN ('recon_radar','unit_refit');
DROP TABLE IF EXISTS technology_rework_targets;
