-- Only northern snow cells in a full 3x3 field survive; all others retain their IDs and state as plains.
CREATE TEMPORARY TABLE retained_northern_snow AS
SELECT DISTINCT w.id
FROM wild_tiles w
JOIN (
    SELECT center.world_id, center.x, center.y
    FROM wild_tiles center
    JOIN wild_tiles neighbor ON neighbor.world_id = center.world_id
        AND neighbor.type = 'snow'
        AND neighbor.x BETWEEN center.x - 1 AND center.x + 1
        AND neighbor.y BETWEEN center.y - 1 AND center.y + 1
    WHERE center.type = 'snow' AND center.y <= 25
    GROUP BY center.world_id, center.x, center.y
    HAVING COUNT(DISTINCT neighbor.x, neighbor.y) = 9
) field ON field.world_id = w.world_id
    AND w.x BETWEEN field.x - 1 AND field.x + 1
    AND w.y BETWEEN field.y - 1 AND field.y + 1
WHERE w.type = 'snow' AND w.y <= 26;

UPDATE wild_tiles SET type = 'plains'
WHERE type = 'snow' AND id NOT IN (SELECT id FROM retained_northern_snow);

DROP TEMPORARY TABLE retained_northern_snow;
