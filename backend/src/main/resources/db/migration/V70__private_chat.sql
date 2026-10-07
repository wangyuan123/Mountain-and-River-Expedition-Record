-- 玩家私聊持久化；未读状态仅由接收方推进。
CREATE TABLE private_chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    content VARCHAR(80) NOT NULL,
    created_at BIGINT NOT NULL,
    read_by_recipient BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_private_sender_recipient (sender_id, recipient_id, id),
    KEY idx_private_recipient_unread (recipient_id, read_by_recipient, sender_id),
    CONSTRAINT fk_private_sender FOREIGN KEY (sender_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_private_recipient FOREIGN KEY (recipient_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
