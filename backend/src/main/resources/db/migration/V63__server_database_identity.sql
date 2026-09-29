-- 已有世界归属江苏一区；新空库由首次启动的后端实例绑定大区。
CREATE TABLE game_server_identity (
    singleton_id TINYINT PRIMARY KEY,
    server_id VARCHAR(40) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO game_server_identity (singleton_id, server_id)
SELECT 1, CASE WHEN EXISTS (SELECT 1 FROM world_map) OR EXISTS (SELECT 1 FROM players)
    THEN 'jiangsu-1' ELSE NULL END;
