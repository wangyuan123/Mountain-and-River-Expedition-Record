#!/usr/bin/env python3
"""
Generate 400x400 quadrant terrain mask (for 800x800 world) and populate Q1 resource wild tiles.

Requirements:
1. Quadrant size 400x400, World size 800x800.
2. "只调整陆地方格数量，不调整海洋方格数量":
   - Sea cells in 400x400 quadrant: EXACTLY 8,812 cells (same as original 200x200).
   - Land cells: EXACTLY 151,188 cells (increased from 31,188).
   - Sea located at eastern edge (X -> 399) and southern edge (Y -> 399) to connect quadrants.
   - Outer border X=399 is 100% sea to connect seamlessly with Q2.
   - Sea is 100% connected with no orphaned pools.
   - Zero conflicts with existing player cities, NPC cities, bandits, and occupied tiles.
3. Resource wild tiles in Q1 (X in [0, 399], Y in [0, 399]):
   - Total 3,300 tiles (4 types * 825).
   - Lv 1~20: 30 per level per type.
   - Lv 21~25: 25 per level per type.
   - Lv 26~30: 20 per level per type.
   - Reserves: level * 160,000.
   - Distribution: Closer to sea -> higher level.
   - 11 occupied player tiles preserved at exact coordinates.
"""

import json
import math
import random
import subprocess
import sys
from collections import deque

WIDTH = 400
HEIGHT = 400
TARGET_SEA = 8812
RESOURCES = ['grainfield', 'ironworks', 'oil', 'rarefactory']

def run_sql(sql):
    proc = subprocess.run(
        ['mysql', '-u', 'root', 'wargame'],
        input=sql.encode('utf-8'),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )
    if proc.returncode != 0:
        print("SQL Error:", proc.stderr.decode('utf-8'), file=sys.stderr)
        sys.exit(1)
    return proc.stdout.decode('utf-8')

def generate_terrain_mask():
    print("==> 1. Generating 400x400 terrain mask with exactly 8,812 sea cells...")
    islands = [
        (386, 84, 5, 8),
        (380, 160, 6, 9),
        (387, 260, 5, 7),
        (375, 340, 7, 6),
        (320, 388, 8, 4),
        (240, 390, 7, 3),
    ]

    scores = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            dx_east = x - (400 - 20 + 4.5 * math.sin(y * 0.04) + 2.5 * math.sin(y * 0.11) - 6.0 * math.exp(-((y - 210)/50.0)**2))
            dy_south = -999.0
            if x > 180:
                dy_south = y - (400 - 11 + 3.0 * math.sin(x * 0.05))
            score = max(dx_east, dy_south)
            for ix, iy, rx, ry in islands:
                dx = (x - ix) / rx
                dy = (y - iy) / ry
                d_island = math.sqrt(dx*dx + dy*dy)
                if d_island < 1.2:
                    score -= (1.2 - d_island) * 30.0
            scores.append((score, x, y))

    scores.sort(key=lambda item: item[0], reverse=True)
    sea_set = {(x, y) for _, x, y in scores[:TARGET_SEA]}
    assert len(sea_set) == TARGET_SEA

    # Check connectivity
    visited = set()
    q = deque()
    for (x, y) in sea_set:
        if x == WIDTH - 1 or y == HEIGHT - 1:
            visited.add((x, y))
            q.append((x, y))
    while q:
        cx, cy = q.popleft()
        for nx, ny in [(cx+1, cy), (cx-1, cy), (cx, cy+1), (cx, cy-1)]:
            if (nx, ny) in sea_set and (nx, ny) not in visited:
                visited.add((nx, ny))
                q.append((nx, ny))
    assert len(visited) == TARGET_SEA, f"Unconnected sea cells: {TARGET_SEA - len(visited)}"

    # Construct string
    mask_chars = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            mask_chars.append('1' if (x, y) in sea_set else '0')
    mask_str = ''.join(mask_chars)
    assert len(mask_str) == WIDTH * HEIGHT
    assert mask_str.count('1') == TARGET_SEA
    assert mask_str.count('0') == WIDTH * HEIGHT - TARGET_SEA

    print(f"    Generated mask length: {len(mask_str)}, Sea: {mask_str.count('1')}, Land: {mask_str.count('0')}")
    return mask_str, sea_set

def populate_wild_tiles(mask_str, sea_set):
    print("==> 2. Inspecting and preserving occupied Q1 wild tiles...")
    occupied_raw = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', """
        SELECT id, type, x, y, level 
        FROM wargame.wild_tiles 
        WHERE (occupied = 1 OR occupied_by IS NOT NULL OR gathering = 1);
    """]).decode().strip().splitlines()

    occupied_tiles = []
    occupied_coords = set()
    occupied_counts = {r: {lv: 0 for lv in range(1, 31)} for r in RESOURCES}

    for line in occupied_raw:
        if not line.strip():
            continue
        parts = line.split()
        tid, rtype, x, y, lv = int(parts[0]), parts[1], int(parts[2]), int(parts[3]), int(parts[4])
        assert (x, y) not in sea_set, f"Occupied tile at ({x}, {y}) is in sea!"
        occupied_tiles.append((tid, rtype, x, y, lv))
        occupied_coords.add((x, y))
        if rtype in occupied_counts and 1 <= lv <= 30:
            occupied_counts[rtype][lv] += 1

    print(f"    Found {len(occupied_tiles)} occupied wild tiles to preserve.")

    # Update occupied tiles reserve to level * 160,000
    if occupied_tiles:
        run_sql("""
            UPDATE wild_tiles 
            SET total_res = level * 160000 
            WHERE (occupied = 1 OR occupied_by IS NOT NULL OR gathering = 1);
        """)

    print("==> 3. Cleaning all unoccupied resource wild tiles across map...")
    run_sql("""
        DELETE FROM wild_tiles 
        WHERE type IN ('grainfield', 'ironworks', 'oil', 'rarefactory')
          AND occupied = 0 AND (occupied_by IS NULL OR occupied_by = 0) AND (gathering = 0 OR gathering IS NULL);
    """)
    # Also clean any wild tiles outside Q1
    run_sql("DELETE FROM wild_tiles WHERE x >= 400 OR y >= 400;")

    print("==> 4. Fetching blocked coordinates (cities, bandits, occupied)...")
    blocked_raw = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', """
        SELECT x, y FROM wargame.player_cities
        UNION
        SELECT x, y FROM wargame.npc_cities
        UNION
        SELECT x, y FROM wargame.bandits
        UNION
        SELECT x, y FROM wargame.wild_tiles;
    """]).decode().strip().splitlines()

    blocked_coords = set()
    for line in blocked_raw:
        if line.strip():
            x, y = map(int, line.split())
            blocked_coords.add((x, y))

    print(f"    Total blocked coordinates: {len(blocked_coords)}")

    print("==> 5. Computing distance-to-sea field for Q1 land...")
    # BFS from all sea cells into land
    dist_to_sea = {}
    q = deque()
    for (sx, sy) in sea_set:
        dist_to_sea[(sx, sy)] = 0
        q.append((sx, sy))

    while q:
        cx, cy = q.popleft()
        cd = dist_to_sea[(cx, cy)]
        for nx, ny in [(cx+1, cy), (cx-1, cy), (cx, cy+1), (cx, cy-1)]:
            if 0 <= nx < WIDTH and 0 <= ny < HEIGHT:
                if (nx, ny) not in dist_to_sea:
                    dist_to_sea[(nx, ny)] = cd + 1
                    q.append((nx, ny))

    # Available land cells in Q1
    available_land = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if (x, y) not in sea_set and (x, y) not in blocked_coords:
                available_land.append((x, y, dist_to_sea.get((x, y), 999)))

    print(f"    Available land cells in Q1: {len(available_land)}")

    # Sort available land by distance to sea (ascending: closest to sea first)
    available_land.sort(key=lambda item: item[2])

    print("==> 6. Preparing quotas and distributing by distance-to-sea...")
    target_quota = {}
    for lv in range(1, 31):
        if lv <= 20:
            target_quota[lv] = 30
        elif lv <= 25:
            target_quota[lv] = 25
        else:
            target_quota[lv] = 20

    # Build tiles by level tier
    tier_high = []   # Lv 26~30 (closest to sea)
    tier_mid = []    # Lv 21~25 (middle distance)
    tier_low = []    # Lv 1~20  (inland)

    for rtype in RESOURCES:
        for lv in range(26, 31):
            needed = target_quota[lv] - occupied_counts[rtype][lv]
            for _ in range(needed):
                tier_high.append((rtype, lv))
        for lv in range(21, 26):
            needed = target_quota[lv] - occupied_counts[rtype][lv]
            for _ in range(needed):
                tier_mid.append((rtype, lv))
        for lv in range(1, 21):
            needed = target_quota[lv] - occupied_counts[rtype][lv]
            for _ in range(needed):
                tier_low.append((rtype, lv))

    total_new = len(tier_high) + len(tier_mid) + len(tier_low)
    assert total_new + len(occupied_tiles) == 3300

    random.seed(20261003)
    random.shuffle(tier_high)
    random.shuffle(tier_mid)
    random.shuffle(tier_low)

    # Allocate land slices according to distance-to-sea
    # Coastal zone: distance 1..35
    # Mid zone: distance 36..80
    # Inland zone: distance 81+
    coastal_cells = [(x, y) for x, y, d in available_land if d <= 35]
    mid_cells = [(x, y) for x, y, d in available_land if 35 < d <= 85]
    inland_cells = [(x, y) for x, y, d in available_land if d > 85]

    print(f"    Land breakdown: Coastal={len(coastal_cells)}, Mid={len(mid_cells)}, Inland={len(inland_cells)}")

    random.shuffle(coastal_cells)
    random.shuffle(mid_cells)
    random.shuffle(inland_cells)

    assigned_tiles = []
    # Assign tier_high to coastal cells
    for i, tile in enumerate(tier_high):
        assigned_tiles.append((tile, coastal_cells[i]))
    rem_coastal = coastal_cells[len(tier_high):]

    # Assign tier_mid to mid cells (or leftover coastal)
    pool_mid = mid_cells + rem_coastal
    random.shuffle(pool_mid)
    for i, tile in enumerate(tier_mid):
        assigned_tiles.append((tile, pool_mid[i]))
    rem_mid = pool_mid[len(tier_mid):]

    # Assign tier_low to inland cells (or leftover mid)
    pool_low = inland_cells + rem_mid
    random.shuffle(pool_low)
    for i, tile in enumerate(tier_low):
        assigned_tiles.append((tile, pool_low[i]))

    print(f"    Total tiles assigned: {len(assigned_tiles)}")

    print("==> 7. Batch inserting 3,300 wild tiles into MySQL...")
    batch_size = 1000
    for i in range(0, len(assigned_tiles), batch_size):
        batch = assigned_tiles[i:i + batch_size]
        values = []
        for (rtype, lv), (x, y) in batch:
            total_res = lv * 160000
            garrison = json.dumps({"infantry": lv * 5})
            values.append(
                f"(1, '{rtype}', {x}, {y}, {lv}, '{garrison}', 0, 0, NULL, {total_res}, 0, 0, 0, 0, 0, 0, NULL, 'manual', 0, NULL, NULL)"
            )
        sql = f"""
            INSERT INTO wild_tiles (
                world_id, type, x, y, level, garrison, scouted, occupied, occupied_by,
                total_res, mined, version, gathering, gather_start_at, gather_end_at,
                gather_load, gather_res, gather_mode, gather_city_slot, gather_harvested, garrison_routes
            ) VALUES {','.join(values)};
        """
        run_sql(sql)
        print(f"    Inserted {min(i + batch_size, len(assigned_tiles))} / {len(assigned_tiles)}...")

    print("\n==> 8. Verifying wild tiles counts in Q1:")
    stats_raw = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', """
        SELECT type, 
               CASE 
                   WHEN level BETWEEN 1 AND 20 THEN 'Lv.01~20'
                   WHEN level BETWEEN 21 AND 25 THEN 'Lv.21~25'
                   WHEN level BETWEEN 26 AND 30 THEN 'Lv.26~30'
               END as bracket,
               COUNT(*),
               COUNT(DISTINCT level),
               MIN(total_res),
               MAX(total_res)
        FROM wargame.wild_tiles
        WHERE x < 400 AND y < 400 AND type IN ('grainfield', 'ironworks', 'oil', 'rarefactory')
        GROUP BY type, bracket
        ORDER BY type, bracket;
    """]).decode().strip().splitlines()

    print(f"{'资源类型':<14} | {'等级区间':<10} | {'总数':<6} | {'等级数':<6} | {'最小储量':<10} | {'最大储量':<10}")
    print("-" * 65)
    for line in stats_raw:
        t, b, cnt, lvcnt, minr, maxr = line.split()
        print(f"{t:<14} | {b:<10} | {cnt:<6} | {lvcnt:<6} | {minr:<10} | {maxr:<10}")

def update_world_map_table(mask_str):
    print("==> 9. Updating world_map record (size=800, terrain_version=2)...")
    # Save mask to a temp file to load safely into MySQL
    with open('/tmp/wargame_terrain_400.txt', 'w') as f:
        f.write(mask_str)
    
    run_sql(f"""
        UPDATE world_map 
        SET size = 800, 
            terrain_version = 2,
            terrain_data = '{mask_str}'
        WHERE id = 1;
    """)
    print("    world_map table successfully updated.")

def main():
    mask_str, sea_set = generate_terrain_mask()
    update_world_map_table(mask_str)
    populate_wild_tiles(mask_str, sea_set)
    print("\n[SUCCESS] 400x400 map and wild tiles successfully configured!")

if __name__ == '__main__':
    main()
