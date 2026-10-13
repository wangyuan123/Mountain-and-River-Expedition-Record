CREATE TABLE city_surrender_offers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    world_id BIGINT NOT NULL,
    initiator_id BIGINT NOT NULL,
    defender_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    city_name VARCHAR(100) NOT NULL,
    city_x INT NOT NULL,
    city_y INT NOT NULL,
    offer_mail_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    sent_at BIGINT NOT NULL,
    response_expires_at BIGINT NOT NULL,
    accepted_at BIGINT NULL,
    transfer_deadline_at BIGINT NULL,
    completed_at BIGINT NULL,
    cancelled_at BIGINT NULL,
    cancel_reason VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_surrender_world FOREIGN KEY (world_id) REFERENCES world_map(id) ON DELETE CASCADE,
    CONSTRAINT fk_surrender_initiator FOREIGN KEY (initiator_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_surrender_defender FOREIGN KEY (defender_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_surrender_city FOREIGN KEY (city_id) REFERENCES player_cities(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_surrender_defender_status ON city_surrender_offers(defender_id, status);
CREATE INDEX idx_surrender_initiator_status ON city_surrender_offers(initiator_id, status);
CREATE INDEX idx_surrender_due ON city_surrender_offers(status, response_expires_at, transfer_deadline_at);
CREATE UNIQUE INDEX uq_surrender_pending_city ON city_surrender_offers(city_id, status);

ALTER TABLE player_cities ADD COLUMN surrender_offer_id BIGINT NULL;
ALTER TABLE player_cities ADD COLUMN surrender_recipient_id BIGINT NULL;
ALTER TABLE player_cities ADD COLUMN surrender_frozen_at BIGINT NULL;
ALTER TABLE player_cities ADD COLUMN surrender_transfer_deadline_at BIGINT NULL;
CREATE INDEX idx_player_city_surrender ON player_cities(surrender_offer_id, surrender_transfer_deadline_at);

ALTER TABLE mails ADD COLUMN surrender_offer_id BIGINT NULL;
CREATE INDEX idx_mail_surrender_offer ON mails(surrender_offer_id);
