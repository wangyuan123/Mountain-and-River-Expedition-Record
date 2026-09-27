CREATE TABLE IF NOT EXISTS market_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_id BIGINT NOT NULL,
    seller_name VARCHAR(64) NOT NULL,
    seller_city_slot INT NOT NULL DEFAULT 0,
    resource_type VARCHAR(16) NOT NULL,
    amount INT NOT NULL,
    price_per_unit INT NOT NULL,
    total_price BIGINT NOT NULL,
    tax_rate DOUBLE NOT NULL DEFAULT 0.1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    buyer_id BIGINT NULL,
    buyer_name VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL
);

CREATE INDEX idx_market_status_res_price ON market_orders(status, resource_type, price_per_unit);
CREATE INDEX idx_market_seller ON market_orders(seller_id, status);
