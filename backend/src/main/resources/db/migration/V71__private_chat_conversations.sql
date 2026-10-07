-- 主动打开的空会话只对发起者展示，不创建虚假消息。
CREATE TABLE private_chat_conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    opened_at BIGINT NOT NULL,
    UNIQUE KEY uk_private_conversation (owner_id, peer_id),
    KEY idx_private_conversation_opened (owner_id, opened_at),
    CONSTRAINT fk_private_conversation_owner FOREIGN KEY (owner_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_private_conversation_peer FOREIGN KEY (peer_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
