-- Retire counter-reconnaissance research, inventory and active effects.
-- Historical scout reports remain snapshots of their original encounters.
DELETE FROM technologies WHERE type = 'recon_stealth';
DELETE FROM player_items WHERE item_key = 'cloak';
ALTER TABLE city_state DROP COLUMN cloak_until;
