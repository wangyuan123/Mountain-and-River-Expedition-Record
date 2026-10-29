-- 旧订单按发布前的人口单价补齐快照；不改数量、排期、资源或现有平民人口。
ALTER TABLE army_production_queue ADD COLUMN cost_population INT NULL;
UPDATE army_production_queue SET cost_population = unit_count * CASE unit_type
    WHEN 'infantry' THEN 1 WHEN 'truck' THEN 1 WHEN 'armored' THEN 2 WHEN 'recon_car' THEN 2
    WHEN 'ltank' THEN 2 WHEN 'htank' THEN 4 WHEN 'assault' THEN 2 WHEN 'rocket' THEN 3
    WHEN 'scout' THEN 1 WHEN 'special' THEN 2 WHEN 'fighter' THEN 2 WHEN 'bomber' THEN 3
    WHEN 'transport' THEN 2 WHEN 'destroyer' THEN 3 WHEN 'sub' THEN 3
    WHEN 'battleship' THEN 6 WHEN 'carrier' THEN 8 ELSE 0 END;

-- 历史部队在首次新征召/完工/解散时，按账号汇总全部城市及在途库存建立成本池。
CREATE TABLE army_population_balances (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    unit_type VARCHAR(50) NOT NULL,
    reference_count BIGINT NOT NULL DEFAULT 0,
    refundable_population BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_army_population_player_type UNIQUE (player_id, unit_type)
);
