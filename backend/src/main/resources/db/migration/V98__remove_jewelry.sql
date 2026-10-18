-- 珠宝系统已移除：清理全部九种珠宝与六种宝箱库存，不转换为晋升凭证或其他奖励。
-- 使用精确道具键，保留军衔凭证、阶段礼包和其他有效道具。
DELETE FROM player_items
WHERE item_key IN (
    'gem_pearl', 'gem_coral', 'gem_glaze', 'gem_amber', 'gem_agate',
    'gem_crystal', 'gem_jadeite', 'gem_jade', 'gem_nightpearl',
    'box_gem', 'box_gem_primary', 'box_gem_medium',
    'box_gem_senior', 'box_gem_supreme', 'box_gem_grand'
);
