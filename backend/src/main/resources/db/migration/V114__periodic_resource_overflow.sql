ALTER TABLE resources MODIFY COLUMN food BIGINT DEFAULT 0;
ALTER TABLE resources MODIFY COLUMN steel BIGINT DEFAULT 0;
ALTER TABLE resources MODIFY COLUMN oil BIGINT DEFAULT 0;
ALTER TABLE resources MODIFY COLUMN rare BIGINT DEFAULT 0;
ALTER TABLE resources MODIFY COLUMN gold BIGINT DEFAULT 0;

CREATE TABLE resource_overflow_state (
    resource_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    stage TINYINT NOT NULL,
    last_period BIGINT NOT NULL,
    PRIMARY KEY (resource_id, resource_type),
    CONSTRAINT fk_resource_overflow_state_resource FOREIGN KEY (resource_id)
        REFERENCES resources(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO game_schedule (task_key, last_period) VALUES ('resource-overflow', 0);
