ALTER TABLE marches
    ADD COLUMN return_stratagem_used BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN order_effects TEXT NULL;

ALTER TABLE players
    ADD COLUMN war_declared_at BIGINT NULL,
    ADD COLUMN war_initiator_id BIGINT NULL,
    ADD COLUMN war_city_slot INT NULL,
    ADD COLUMN war_target_city_id BIGINT NULL,
    ADD COLUMN war_accelerated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN war_order_effect TEXT NULL;
