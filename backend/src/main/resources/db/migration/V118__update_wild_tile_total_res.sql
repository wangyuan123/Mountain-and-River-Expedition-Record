UPDATE wild_tiles
SET total_res = CASE
        WHEN total_res > mined THEN total_res - mined
        ELSE 0
    END,
    mined = 0
WHERE mined > 0 AND total_res > 0;
