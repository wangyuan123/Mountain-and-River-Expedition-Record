-- 保留历史会话的 NULL，由回合结算读取既有战报恢复首次交战回合。
ALTER TABLE battle_sessions ADD COLUMN first_combat_round INT NULL;
