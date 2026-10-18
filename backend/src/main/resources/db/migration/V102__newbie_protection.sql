-- 历史账号保留0，不因升级重新领取；新注册账号由服务端写NULL，首次进入时才开始计时。
ALTER TABLE players ADD COLUMN newbie_protection_started_at BIGINT DEFAULT 0;
ALTER TABLE players ADD COLUMN newbie_protection_ended BOOLEAN NOT NULL DEFAULT FALSE;
