-- 部署到位与预计交火独立持久化；旧行军保持普通迎击，不追溯给予伏击优势。
ALTER TABLE marches ADD COLUMN intercept_deploy_at BIGINT NULL;
ALTER TABLE marches ADD COLUMN intercept_ready_at BIGINT NULL;
ALTER TABLE marches ADD COLUMN intercept_enemy_progress DOUBLE NULL;
ALTER TABLE marches ADD COLUMN intercept_enemy_start_at BIGINT NULL;
ALTER TABLE marches ADD COLUMN intercept_route_snapshot LONGTEXT NULL;
ALTER TABLE marches ADD COLUMN ambush_used BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE incoming_marches ADD COLUMN ambush_used BOOLEAN NOT NULL DEFAULT FALSE;
