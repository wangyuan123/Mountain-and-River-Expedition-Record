ALTER TABLE players ADD COLUMN civilian_population INT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN population_growth_remainder DOUBLE NOT NULL DEFAULT 0;

UPDATE players p
SET civilian_population = GREATEST(0,
    COALESCE((SELECT SUM(b.level) * 100 FROM buildings b WHERE b.player_id = p.id AND b.type = 'house'), 0)
    - COALESCE((SELECT SUM(CASE au.type
        WHEN 'infantry' THEN au.count WHEN 'motor' THEN au.count WHEN 'truck' THEN au.count
        WHEN 'armored' THEN au.count * 2 WHEN 'ltank' THEN au.count * 2
        WHEN 'htank' THEN au.count * 4 WHEN 'assault' THEN au.count * 3 WHEN 'rocket' THEN au.count * 4
        WHEN 'scout' THEN au.count WHEN 'special' THEN au.count * 2 WHEN 'fighter' THEN au.count * 2
        WHEN 'bomber' THEN au.count * 3 WHEN 'transport' THEN au.count * 2
        WHEN 'destroyer' THEN au.count * 3 WHEN 'sub' THEN au.count * 3
        WHEN 'battleship' THEN au.count * 6 WHEN 'carrier' THEN au.count * 8
        ELSE 0 END) FROM army_units au WHERE au.player_id = p.id), 0)
);
