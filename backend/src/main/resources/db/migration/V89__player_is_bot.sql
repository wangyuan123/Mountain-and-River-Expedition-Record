-- V89: 增加模拟玩家标记字段，用于管理后台批量识别与清理
ALTER TABLE players ADD COLUMN is_bot TINYINT(1) NOT NULL DEFAULT 0;

-- 将历史生成的模拟玩家数据进行回填
UPDATE players SET is_bot = 1 WHERE username LIKE '远征军_%' OR username LIKE 'testbot_%' OR username LIKE 'bot_%';
