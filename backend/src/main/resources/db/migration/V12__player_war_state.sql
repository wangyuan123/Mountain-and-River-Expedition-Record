-- =====================================================
-- V12: Player-level war state (双向宣战)
--  - war_at        : 备战结束、进入交战的时间戳
--  - war_end_at    : 战争结束时间戳
--  - war_against_id: 与之宣战的真实玩家 player.id (NULL 表示无)
-- =====================================================

ALTER TABLE players ADD COLUMN war_at BIGINT DEFAULT 0;
ALTER TABLE players ADD COLUMN war_end_at BIGINT DEFAULT 0;
ALTER TABLE players ADD COLUMN war_against_id BIGINT DEFAULT NULL;

CREATE INDEX idx_players_war_against ON players (war_against_id);
