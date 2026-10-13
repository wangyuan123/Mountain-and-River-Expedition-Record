-- Existing ranks remain valid; combat victories start counting with this version.
ALTER TABLE players ADD COLUMN npc_victories BIGINT NOT NULL DEFAULT 0;
