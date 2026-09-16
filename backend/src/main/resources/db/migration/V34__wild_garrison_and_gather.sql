-- Wild tile garrison, gathering and harvesting
ALTER TABLE wild_tiles ADD COLUMN gathering BOOLEAN DEFAULT FALSE;
ALTER TABLE wild_tiles ADD COLUMN gather_start_at BIGINT DEFAULT 0;
ALTER TABLE wild_tiles ADD COLUMN gather_end_at BIGINT DEFAULT 0;
ALTER TABLE wild_tiles ADD COLUMN gather_load INT DEFAULT 0;
ALTER TABLE wild_tiles ADD COLUMN gather_res VARCHAR(50) DEFAULT NULL;
