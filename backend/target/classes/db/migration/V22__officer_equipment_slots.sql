ALTER TABLE officer_equipment
    ADD COLUMN slot VARCHAR(20) NOT NULL DEFAULT 'weapon' AFTER tier,
    ADD COLUMN equipped_at BIGINT NOT NULL DEFAULT 0 AFTER created_at;

CREATE UNIQUE INDEX uk_officer_equipment_slot
    ON officer_equipment (officer_id, slot);
