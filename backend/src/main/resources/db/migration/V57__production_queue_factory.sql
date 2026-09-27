ALTER TABLE army_production_queue ADD COLUMN building_type VARCHAR(50) NULL;

-- Existing aircraft orders keep the factory lane that accepted and charged for them.
UPDATE army_production_queue SET building_type = 'factory'
WHERE unit_type IN ('fighter', 'bomber', 'transport');
