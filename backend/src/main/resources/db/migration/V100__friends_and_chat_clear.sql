-- 好友、分组和黑名单按账号持久化；默认分组不建行，以 NULL group_id 表示。
CREATE TABLE friend_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(20) NOT NULL,
    UNIQUE KEY uk_friend_group (owner_id, name),
    CONSTRAINT fk_friend_group_owner FOREIGN KEY (owner_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE friend_contacts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    is_friend BOOLEAN NOT NULL DEFAULT FALSE,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_friend_contact (owner_id, peer_id),
    KEY idx_friend_contact_group (owner_id, group_id),
    CONSTRAINT fk_friend_contact_owner FOREIGN KEY (owner_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_contact_peer FOREIGN KEY (peer_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_contact_group FOREIGN KEY (group_id) REFERENCES friend_groups(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 清空与隐藏会话分开保存：重新打开会话不能恢复自己已清空的消息。
ALTER TABLE private_chat_conversations ADD COLUMN cleared_through BIGINT NOT NULL DEFAULT 0;

ALTER TABLE players ADD COLUMN friend_request_policy VARCHAR(16) NOT NULL DEFAULT 'APPROVAL';
ALTER TABLE players ADD COLUMN block_stranger_messages BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE friend_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_friend_request (sender_id, recipient_id),
    KEY idx_friend_request_recipient (recipient_id, id),
    CONSTRAINT fk_friend_request_sender FOREIGN KEY (sender_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_request_recipient FOREIGN KEY (recipient_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
