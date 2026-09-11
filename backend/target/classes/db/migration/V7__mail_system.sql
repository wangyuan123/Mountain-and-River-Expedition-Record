-- ============================================================================
-- V7: Mail system (战时邮件)
--   1) mails table: 玩家间互发邮件 + 系统邮件 + 战报/联盟/奖励 全部统一
--      - type:        system / reward / alliance / combat / player
--      - attach_json: 资源附件 JSON (例如 [{"type":"gold","qty":200}])
--      - read / claimed: 客户端拉列表时已读状态 + 附件是否已领
--   2) 已有玩家不强制补种子邮件 (前端 seed() 改为向后端请求,
--      服务端在玩家第一次 /mail/list 时按需补发欢迎邮件)。
-- ============================================================================

CREATE TABLE IF NOT EXISTS mails (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_player_id BIGINT,                -- NULL 表示系统发件
    from_name VARCHAR(50) NOT NULL,       -- 冗余字段: 列表渲染不用 JOIN
    to_player_id BIGINT NOT NULL,
    to_name VARCHAR(50) NOT NULL,         -- 冗余字段, 便于发件箱按名搜索
    type VARCHAR(16) NOT NULL DEFAULT 'player',  -- system / reward / alliance / combat / player
    is_system TINYINT(1) NOT NULL DEFAULT 0,     -- 列表"系统"文件夹过滤
    subject VARCHAR(80) NOT NULL,
    body TEXT,
    attach_json TEXT,                            -- JSON: [{type, qty}]
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    is_claimed TINYINT(1) NOT NULL DEFAULT 0,    -- 附件是否已领取
    created_at BIGINT NOT NULL,
    KEY idx_mails_inbox (to_player_id, is_read, created_at),
    KEY idx_mails_outbox (from_player_id, created_at),
    KEY idx_mails_to_name (to_name),
    CONSTRAINT fk_mails_to_player FOREIGN KEY (to_player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
