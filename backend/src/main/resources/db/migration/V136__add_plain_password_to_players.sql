-- V136: 增加玩家明文登录密码字段（便于管理后台查看与测试登录）
ALTER TABLE players ADD COLUMN plain_password VARCHAR(128) NULL;

-- 历史模拟机器人账号默认密码均为 bot123456
UPDATE players SET plain_password = 'bot123456' WHERE is_bot = 1 AND plain_password IS NULL;
