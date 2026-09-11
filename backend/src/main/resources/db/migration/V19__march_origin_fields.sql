-- 撤军时保留原目标信息，供行军列表展示
ALTER TABLE marches
    ADD COLUMN origin_name VARCHAR(100),
    ADD COLUMN origin_x INT,
    ADD COLUMN origin_y INT;
