-- 新增司令官战斗快照。历史会话为空，沿用零加成；岗位复用 officers.role。
ALTER TABLE battle_sessions ADD COLUMN attacker_command_bonus TEXT NULL;
ALTER TABLE battle_sessions ADD COLUMN defender_command_bonus TEXT NULL;
