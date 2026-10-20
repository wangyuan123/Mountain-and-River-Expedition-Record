-- 0 表示尚未启用：首次启动使用旧版最后结算点；之后始终从已提交的心跳恢复。
CREATE TABLE game_clock (
    singleton_id INT NOT NULL PRIMARY KEY,
    game_time BIGINT NOT NULL,
    owner_token VARCHAR(36),
    heartbeat_at BIGINT NOT NULL DEFAULT 0
);
INSERT INTO game_clock (singleton_id, game_time) VALUES (1, 0);

CREATE TABLE game_schedule (
    task_key VARCHAR(64) NOT NULL PRIMARY KEY,
    last_period BIGINT NOT NULL
);
INSERT INTO game_schedule (task_key, last_period) VALUES ('world-refresh', 0);
