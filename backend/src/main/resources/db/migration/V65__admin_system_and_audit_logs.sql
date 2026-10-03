-- Admin Users Table
CREATE TABLE IF NOT EXISTS admin_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    disabled INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Initial default admin: admin / admin123456
INSERT INTO admin_users (username, password_hash, role, disabled, created_at, updated_at)
VALUES ('admin', '$2a$10$mC/yoWP5YqabRpdNOVgUCewGbyw8qiodZplD3i/hJEYMe4S4VTX1i', 'SUPER_ADMIN', 0, 1727654400000, 1727654400000)
ON DUPLICATE KEY UPDATE id=id;

-- Admin Operation Logs Table
CREATE TABLE IF NOT EXISTS admin_operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_username VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) DEFAULT NULL,
    target_id VARCHAR(50) DEFAULT NULL,
    detail TEXT DEFAULT NULL,
    ip_address VARCHAR(50) DEFAULT NULL,
    created_at BIGINT NOT NULL,
    INDEX idx_admin_logs_admin (admin_username),
    INDEX idx_admin_logs_action (action_type),
    INDEX idx_admin_logs_target (target_type, target_id),
    INDEX idx_admin_logs_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Player ban support
ALTER TABLE players ADD COLUMN ban_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE players ADD COLUMN ban_reason VARCHAR(255) DEFAULT NULL;
ALTER TABLE players ADD COLUMN banned_until BIGINT DEFAULT NULL;
