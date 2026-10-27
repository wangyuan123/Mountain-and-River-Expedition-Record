-- 每日任务与军政签到：记录玩家每日自主领受改名卡与战备签到日期
ALTER TABLE players ADD COLUMN last_daily_checkin_date DATE DEFAULT NULL;
