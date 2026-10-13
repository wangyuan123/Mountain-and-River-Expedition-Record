-- 阶段任务实体继承VersionedEntity，补齐乐观锁列以通过启动时的结构校验。
ALTER TABLE player_quest_stages ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
