-- Forum Boards Table
CREATE TABLE IF NOT EXISTS forum_boards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    board_key VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255) DEFAULT '',
    icon VARCHAR(50) DEFAULT 'Document',
    min_prestige INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    topic_count INT NOT NULL DEFAULT 0,
    post_count INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Initial Boards
INSERT INTO forum_boards (board_key, name, description, icon, min_prestige, sort_order, topic_count, post_count) VALUES
('strategy', '参谋本部', '作战指导、配兵克制、将领培养与野地攻坚心得', 'Compass', 0, 1, 0, 0),
('tavern', '战地茶馆', '前线见闻、军友闲谈、同盟吹水交流区', 'Coffee', 0, 2, 0, 0),
('legion', '军团动员处', '远征军各军团招募联络、阵营联合檄文与公约', 'Flag', 0, 3, 0, 0),
('battle_hall', '战功英雄榜', '经典大捷战报复盘、史诗攻防战观摩与讨论', 'Trophy', 0, 4, 0, 0),
('notice', '后勤联络部', '指挥部更新公告、战备Bug反馈与参谋提议', 'Bell', 0, 5, 0, 0)
ON DUPLICATE KEY UPDATE id=id;

-- Forum Topics Table
CREATE TABLE IF NOT EXISTS forum_topics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    board_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    author_name VARCHAR(50) NOT NULL,
    author_display_name VARCHAR(100) DEFAULT NULL,
    author_avatar VARCHAR(255) DEFAULT NULL,
    author_military_rank INT NOT NULL DEFAULT 1,
    author_prestige INT NOT NULL DEFAULT 0,
    author_guild_name VARCHAR(50) DEFAULT NULL,
    title VARCHAR(120) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    topic_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    bounty_gold INT NOT NULL DEFAULT 0,
    bounty_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    bounty_accepted_reply_id BIGINT DEFAULT NULL,
    battle_report_id BIGINT DEFAULT NULL,
    view_count INT NOT NULL DEFAULT 0,
    reply_count INT NOT NULL DEFAULT 0,
    like_count INT NOT NULL DEFAULT 0,
    tip_count INT NOT NULL DEFAULT 0,
    tip_total_gold INT NOT NULL DEFAULT 0,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    is_essence BOOLEAN NOT NULL DEFAULT FALSE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_forum_topics_board (board_id),
    INDEX idx_forum_topics_author (author_id),
    INDEX idx_forum_topics_created (created_at),
    INDEX idx_forum_topics_pinned_created (is_pinned, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Forum Replies Table
CREATE TABLE IF NOT EXISTS forum_replies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    author_name VARCHAR(50) NOT NULL,
    author_display_name VARCHAR(100) DEFAULT NULL,
    author_avatar VARCHAR(255) DEFAULT NULL,
    author_military_rank INT NOT NULL DEFAULT 1,
    author_prestige INT NOT NULL DEFAULT 0,
    author_guild_name VARCHAR(50) DEFAULT NULL,
    content TEXT NOT NULL,
    floor_number INT NOT NULL DEFAULT 1,
    quote_reply_id BIGINT DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    is_accepted_bounty BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    INDEX idx_forum_replies_topic (topic_id),
    INDEX idx_forum_replies_author (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Forum Likes Table
CREATE TABLE IF NOT EXISTS forum_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_forum_likes (target_type, target_id, player_id),
    INDEX idx_forum_likes_target (target_type, target_id),
    INDEX idx_forum_likes_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Forum Tips Table
CREATE TABLE IF NOT EXISTS forum_tips (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_name VARCHAR(50) NOT NULL,
    amount_gold INT NOT NULL,
    message VARCHAR(255) DEFAULT '',
    created_at BIGINT NOT NULL,
    INDEX idx_forum_tips_topic (topic_id),
    INDEX idx_forum_tips_sender (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
