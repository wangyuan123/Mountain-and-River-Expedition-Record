ALTER TABLE players
    ADD COLUMN world_chat_unlocked BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing player chat messages could only have been sent after reaching the old threshold.
UPDATE players
SET world_chat_unlocked = TRUE
WHERE COALESCE(prestige, 0) >= 10000
   OR id IN (SELECT player_id FROM chat_messages WHERE player_id IS NOT NULL);
