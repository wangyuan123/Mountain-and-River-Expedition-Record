ALTER TABLE admin_users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 1;

-- 只停用旧迁移植入的固定凭据，保留管理员自己更换过的密码；历史无版本 JWT 一律失效。
UPDATE admin_users
SET disabled = 1, auth_version = auth_version + 1
WHERE username = 'admin'
  AND password_hash = '$2a$10$mC/yoWP5YqabRpdNOVgUCewGbyw8qiodZplD3i/hJEYMe4S4VTX1i';
