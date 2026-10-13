-- 锁定本次参战的玩家城指挥官；历史会话仍按原城市当前指挥官兼容读取。
ALTER TABLE battle_sessions ADD COLUMN defender_commander_id BIGINT NULL;
