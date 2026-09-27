-- 为旧账号的空头像、已删除 SVG 或外部地址随机分配一张历史头像，并持久保存。
-- 已主动选择新头像的玩家保持原选择；已完成注销的账号保持清理后的状态。
UPDATE players
SET avatar = CASE FLOOR(RAND() * 8)
    WHEN 0 THEN 'img/avatars/historical/rank-private-v1.webp'
    WHEN 1 THEN 'img/avatars/historical/rank-corporal-v1.webp'
    WHEN 2 THEN 'img/avatars/historical/rank-sergeant-v1.webp'
    WHEN 3 THEN 'img/avatars/historical/rank-lieutenant-v1.webp'
    WHEN 4 THEN 'img/avatars/historical/rank-captain-v1.webp'
    WHEN 5 THEN 'img/avatars/historical/rank-major-v1.webp'
    WHEN 6 THEN 'img/avatars/historical/rank-colonel-v1.webp'
    ELSE 'img/avatars/historical/rank-general-v1.webp'
END, version = version + 1
WHERE account_status <> 'DELETED'
  AND (avatar IS NULL OR avatar NOT IN (
    'img/avatars/historical/rank-private-v1.webp',
    'img/avatars/historical/rank-corporal-v1.webp',
    'img/avatars/historical/rank-sergeant-v1.webp',
    'img/avatars/historical/rank-lieutenant-v1.webp',
    'img/avatars/historical/rank-captain-v1.webp',
    'img/avatars/historical/rank-major-v1.webp',
    'img/avatars/historical/rank-colonel-v1.webp',
    'img/avatars/historical/rank-general-v1.webp'
  ));
