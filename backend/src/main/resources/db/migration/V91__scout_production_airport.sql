-- 侦察机改为空军基地生产；已支付的历史订单继续占用原军工厂通道。
UPDATE army_production_queue SET building_type = 'factory'
WHERE unit_type = 'scout' AND building_type IS NULL;
