-- 删除边界仅影响会话所有者，新消息自动恢复会话。
ALTER TABLE private_chat_conversations ADD COLUMN deleted_through BIGINT NOT NULL DEFAULT 0;
