-- 城防等级只管理工事容量，战斗会话不再冻结或使用城墙防御加成。
ALTER TABLE battle_sessions DROP COLUMN defender_wall_level;
