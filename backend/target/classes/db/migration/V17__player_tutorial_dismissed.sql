-- V17: persist whether a player has dismissed the tutorial
ALTER TABLE players ADD COLUMN tutorial_dismissed BOOLEAN NOT NULL DEFAULT FALSE;
