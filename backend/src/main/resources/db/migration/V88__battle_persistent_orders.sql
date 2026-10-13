-- 本场战斗独立保存攻守双方指令；历史会话为空时继续使用账号默认战术。
ALTER TABLE battle_sessions ADD COLUMN attacker_orders TEXT NULL;
ALTER TABLE battle_sessions ADD COLUMN defender_orders TEXT NULL;
