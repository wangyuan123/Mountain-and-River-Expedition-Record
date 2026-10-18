-- 开发阶段移除未上线的活动与订阅商品，旧库存直接清理，不发放替代奖励。
DELETE FROM player_items WHERE item_key IN ('annivPack', 'monthCard', 'warChest');
