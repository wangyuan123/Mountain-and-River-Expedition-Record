-- 系统开箱广播需要容纳完整奖励；玩家发言仍由 ChatService 限制为 80 字。
ALTER TABLE chat_messages MODIFY COLUMN content VARCHAR(512) NOT NULL;
