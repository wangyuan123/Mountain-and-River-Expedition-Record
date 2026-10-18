-- 历史会话保留 NULL，不在战斗中途补入墙体；新玩家城征服战冻结独立结构属性。
ALTER TABLE battle_sessions ADD COLUMN wall_level_snapshot INT NULL;
ALTER TABLE battle_sessions ADD COLUMN wall_max_hp DECIMAL(20,6) NULL;
ALTER TABLE battle_sessions ADD COLUMN wall_hp_remaining DECIMAL(20,6) NULL;
ALTER TABLE battle_sessions ADD COLUMN wall_defense INT NULL;
ALTER TABLE battle_sessions ADD COLUMN battle_phase VARCHAR(30) NULL;
ALTER TABLE battle_sessions ADD COLUMN battle_rules_version VARCHAR(100) NULL;
