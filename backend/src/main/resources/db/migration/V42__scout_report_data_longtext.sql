-- 战术战斗的逐回合日志可能超过 TEXT 的 64KB 容量，必须完整保留以供战报详情查看。
ALTER TABLE scout_reports MODIFY COLUMN data LONGTEXT;
