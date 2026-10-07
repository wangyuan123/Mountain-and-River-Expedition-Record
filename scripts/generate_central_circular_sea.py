#!/usr/bin/env python3
"""
Generate 400x400 quadrant terrain mask with:
1. Central circular sea: exactly 2 * 8,812 = 17,624 cells per quadrant (70,496 in 800x800 world).
2. Vertical and horizontal ocean waterways (河道) connecting to the center:
   - Vertical waterway along eastern border X -> 399
   - Horizontal waterway along southern border Y -> 399
   - When mirrored, forms a cross waterway intersecting the central circular ocean.
3. 3,300 resource wild tiles in Q1 (X in [0, 399], Y in [0, 399]):
   - Graded: closer to sea (central circle and waterways) -> higher level (Lv 26~30).
   - Inland -> lower level (Lv 1~20).
   - Preserves 11 occupied player wild tiles at exact coordinates.
   - Quotas: 30/lv for 1~20, 25/lv for 21~25, 20/lv for 26~30 across 4 resource types.
"""

import json
import math
import random
import subprocess
import sys
from collections import deque

WIDTH = 400
HEIGHT = 400
TARGET_CIRCLE = 8812 * 2  # 17624
cx = 399.5
cy = 399.5
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
    print(f"==> 1. Generating central circular sea (target: {TARGET_CIRCLE} cells) with cross waterways...")
    circle_scores = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            dx = x - cx
            dy = y - cy
            dist = math.hypot(dx, dy)
            angle = math.atan2(dy, dx)
            # Natural shoreline variation
            wave_circle = 3.0 * math.sin(angle * 6 + 1.2) + 1.5 * math.sin(angle * 14 + 0.5)
            score = -(dist - wave_circle)
            circle_scores.append((score, x, y))

    circle_scores.sort(key=lambda item: item[0], reverse=True)
    circle_sea_set = {(x, y) for _, x, y in circle_scores[:TARGET_CIRCLE]}
    assert len(circle_sea_set) == TARGET_CIRCLE

    # Add vertical and horizontal ocean waterways (connecting to center)
    w_v = 10
    w_h = 10
    waterway_cells = set()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            wave_v = 1.6 * math.sin(y * 0.05) + 0.8 * math.sin(y * 0.13)
            if x >= (400 - w_v) - wave_v:
                waterway_cells.add((x, y))
            wave_h = 1.6 * math.sin(x * 0.05) + 0.8 * math.sin(x * 0.13)
            if y >= (400 - w_h) - wave_h:
                waterway_cells.add((x, y))

    total_sea_set = circle_sea_set.union(waterway_cells)
    print(f"    Circle cells: {len(circle_sea_set)} (exactly 2x 8,812)")
    print(f"    Waterway extra cells: {len(total_sea_set) - len(circle_sea_set)}")
    print(f"    Total quadrant sea cells: {len(total_sea_set)}")
    print(f"    Total quadrant land cells: {WIDTH * HEIGHT - len(total_sea_set)}")

    # Verify 100% connectivity to borders
    visited = set()
    q = deque()
    for (x, y) in total_sea_set:
        if x == WIDTH - 1 or y == HEIGHT - 1:
            visited.add((x, y))
            q.append((x, y))

    while q:
        px, py = q.popleft()
        for nx, ny in [(px+1, py), (px-1, py), (px, py+1), (px, py-1)]:
            if (nx, ny) in total_sea_set and (nx, ny) not in visited:
                visited.add((nx, ny))
                q.append((nx, ny))

    assert len(visited) == len(total_sea_set), f"Unconnected sea cells: {len(total_sea_set) - len(visited)}"
    print("    Sea connectivity: 100% connected to quadrant borders.")

    # Construct mask string
    mask_chars = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            mask_chars.append('1' if (x, y) in total_sea_set else '0')
    mask_str = ''.join(mask_chars)
    assert len(mask_str) == WIDTH * HEIGHT

    return mask_str, total_sea_set, circle_sea_set

def populate_wild_tiles(mask_str, total_sea_set):
    print("==> 2. Checking existing occupied wild tiles...")
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
        assert (x, y) not in total_sea_set, f"Occupied tile at ({x}, {y}) is in sea!"
        occupied_tiles.append((tid, rtype, x, y, lv))
        occupied_coords.add((x, y))
        if rtype in occupied_counts and 1 <= lv <= 30:
            occupied_counts[rtype][lv] += 1

    print(f"    Preserving {len(occupied_tiles)} occupied wild tiles.")

    # Update occupied tiles reserve to level * 160,000
    if occupied_tiles:
        run_sql("""
            UPDATE wild_tiles 
            SET total_res = level * 160000 
            WHERE (occupied = 1 OR occupied_by IS NOT NULL OR gathering = 1);
        """)

    print("==> 3. Cleaning all unoccupied resource wild tiles...")
    run_sql("""
        DELETE FROM wild_tiles 
        WHERE type IN ('grainfield', 'ironworks', 'oil', 'rarefactory')
          AND occupied = 0 AND (occupied_by IS NULL OR occupied_by = 0) AND (gathering = 0 OR gathering IS NULL);
    """)
    run_sql("DELETE FROM wild_tiles WHERE x >= 400 OR y >= 400;")

    print("==> 4. Fetching blocked coordinates...")
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

    print(f"    Blocked coordinates count: {len(blocked_coords)}")

    print("==> 5. Computing distance-to-sea field for Q1 land...")
    dist_to_sea = {}
    q = deque()
    for (sx, sy) in total_sea_set:
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

    available_land = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if (x, y) not in total_sea_set and (x, y) not in blocked_coords:
                available_land.append((x, y, dist_to_sea.get((x, y), 999)))

    print(f"    Available land cells: {len(available_land)}")
    available_land.sort(key=lambda item: item[2])

    print("==> 6. Assigning tiles by distance-to-sea gradient...")
    target_quota = {}
    for lv in range(1, 31):
        if lv <= 20:
            target_quota[lv] = 30
        elif lv <= 25:
            target_quota[lv] = 25
        else:
            target_quota[lv] = 20

    tier_high = []   # Lv 26~30 (closest to circular sea and waterways)
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

    # Slice by distance to sea:
    # Coastal band (closest to circular sea & waterways): dist <= 25
    # Mid band: 25 < dist <= 60
    # Inland band: dist > 60
    coastal_cells = [(x, y) for x, y, d in available_land if d <= 25]
    mid_cells = [(x, y) for x, y, d in available_land if 25 < d <= 65]
    inland_cells = [(x, y) for x, y, d in available_land if d > 65]

    print(f"    Land slices: Coastal={len(coastal_cells)}, Mid={len(mid_cells)}, Inland={len(inland_cells)}")
    random.shuffle(coastal_cells)
    random.shuffle(mid_cells)
    random.shuffle(inland_cells)

    assigned_tiles = []
    # Assign tier_high
    for i, tile in enumerate(tier_high):
        assigned_tiles.append((tile, coastal_cells[i]))
    rem_coastal = coastal_cells[len(tier_high):]

    # Assign tier_mid
    pool_mid = mid_cells + rem_coastal
    random.shuffle(pool_mid)
    for i, tile in enumerate(tier_mid):
        assigned_tiles.append((tile, pool_mid[i]))
    rem_mid = pool_mid[len(tier_mid):]

    # Assign tier_low
    pool_low = inland_cells + rem_mid
    random.shuffle(pool_low)
    for i, tile in enumerate(tier_low):
        assigned_tiles.append((tile, pool_low[i]))

    print(f"    Total assigned tiles: {len(assigned_tiles)}")

    print("==> 7. Batch inserting into MySQL...")
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

    print("\n==> 8. Verifying counts by bracket in Q1:")
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
    print("==> 9. Updating world_map record...")
    run_sql(f"""
        UPDATE world_map 
        SET size = 800, 
            terrain_version = 2,
            terrain_data = '{mask_str}'
        WHERE id = 1;
    """)
    print("    world_map table successfully updated.")

def main():
    mask_str, total_sea_set, circle_sea_set = generate_terrain_mask()
    update_world_map_table(mask_str)
    populate_wild_tiles(mask_str, total_sea_set)
    print("\n[SUCCESS] Central circular ocean and wild tiles configured successfully!")

if __name__ == '__main__':
    main()
