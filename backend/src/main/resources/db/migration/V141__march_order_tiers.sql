-- 原行军令库存键保持不变，继续对应 50% 极速档；已启用效果也保留原来的 50%。
ALTER TABLE city_state ADD COLUMN march_boost_percent INT NOT NULL DEFAULT 50;
