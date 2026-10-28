-- 清理任务按头像路径检查引用，避免每个文件都扫描全部玩家。
CREATE INDEX idx_players_custom_avatar ON players (custom_avatar);
