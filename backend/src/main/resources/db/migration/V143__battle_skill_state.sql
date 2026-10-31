-- 校射、屏障、承伤与追击只属于当前战斗；历史会话从已冻结开战兵力初始化。
ALTER TABLE battle_sessions ADD COLUMN skill_state LONGTEXT NULL;
