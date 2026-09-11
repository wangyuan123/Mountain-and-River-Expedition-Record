-- V16: allow persistent system messages in world chat
ALTER TABLE chat_messages MODIFY COLUMN player_id BIGINT NULL;
