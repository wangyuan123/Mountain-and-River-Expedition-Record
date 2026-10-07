#!/usr/bin/env python3
"""
Move halved resource wild tiles to Quadrant 1 (Northwest continent, X: 0~199, Y: 0~199).
Requirements:
1. Q2 wild tiles added earlier are removed.
2. Halved counts for each level and each of the 4 resource types:
   - Lv 1~20: 60 tiles per level (was 120)
   - Lv 21~25: 50 tiles per level (was 100)
   - Lv 26~30: 40 tiles per level (was 80)
   Total per type: 20*60 + 5*50 + 5*40 = 1,650 tiles.
   4 types (grainfield, ironworks, oil, rarefactory) * 1,650 = 6,600 tiles total.
3. Total reserves: level * 160,000 (Lv 8 = 1,280,000, Lv 30 = 4,800,000).
4. Distributed randomly across land in Q1 (X in [0, 199], Y in [0, 199]).
5. Protect occupied Q1 wild tiles (update total_res to level * 160000 and count towards the quota).
6. Avoid player cities, NPC cities, sea cells, and terrain tiles.
"""

import json
import random
import subprocess
import sys

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

def main():
    print("==> 1. Fetching terrain mask...")
    mask = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', 'SELECT terrain_data FROM wargame.world_map WHERE id=1;']).decode().strip()
    assert len(mask) == 40000, f"Unexpected terrain mask length: {len(mask)}"

    # 1. Clean Q2 wild tiles
    print("==> 2. Removing wild tiles from Q2 (x >= 200)...")
    run_sql("DELETE FROM wild_tiles WHERE x >= 200;")

    # 2. Get existing occupied Q1 wild tiles to preserve
    print("==> 3. Inspecting Q1 occupied wild tiles...")
    occupied_raw = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', """
        SELECT id, type, x, y, level 
        FROM wargame.wild_tiles 
        WHERE x < 200 AND y < 200 AND (occupied = 1 OR occupied_by IS NOT NULL OR gathering = 1);
    """]).decode().strip().splitlines()

    occupied_tiles = []
    occupied_coords = set()
    occupied_counts = {r: {lv: 0 for lv in range(1, 31)} for r in RESOURCES}

    for line in occupied_raw:
        if not line.strip():
            continue
        parts = line.split()
        tid, rtype, x, y, lv = int(parts[0]), parts[1], int(parts[2]), int(parts[3]), int(parts[4])
        occupied_tiles.append((tid, rtype, x, y, lv))
        occupied_coords.add((x, y))
        if rtype in occupied_counts and 1 <= lv <= 30:
            occupied_counts[rtype][lv] += 1

    print(f"    Found {len(occupied_tiles)} occupied wild tiles in Q1 to preserve.")

    # Update occupied tiles reserve to level * 160,000
    if occupied_tiles:
        run_sql("""
            UPDATE wild_tiles 
            SET total_res = level * 160000 
            WHERE x < 200 AND y < 200 AND (occupied = 1 OR occupied_by IS NOT NULL OR gathering = 1);
        """)

    # 3. Clean old unoccupied resource wild tiles in Q1
    print("==> 4. Cleaning old unoccupied resource wild tiles in Q1...")
    run_sql("""
        DELETE FROM wild_tiles 
        WHERE x < 200 AND y < 200 
          AND type IN ('grainfield', 'ironworks', 'oil', 'rarefactory')
          AND occupied = 0 AND (occupied_by IS NULL OR occupied_by = 0) AND (gathering = 0 OR gathering IS NULL);
    """)

    # 4. Fetch cities coordinates and non-resource terrain tiles coordinates to avoid
    print("==> 5. Fetching cities and existing wild coordinates to avoid...")
    cities_raw = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', """
        SELECT x, y FROM wargame.player_cities
        UNION
        SELECT x, y FROM wargame.npc_cities
        UNION
        SELECT x, y FROM wargame.wild_tiles WHERE x < 200 AND y < 200;
    """]).decode().strip().splitlines()

    blocked_coords = set()
    for line in cities_raw:
        if line.strip():
            x, y = map(int, line.split())
            blocked_coords.add((x, y))

    print(f"    Total blocked coordinates in Q1: {len(blocked_coords)}")

    # 5. Collect available land cells in Q1 (X in [0, 199], Y in [0, 199])
    q1_available_land = []
    for y in range(200):
        for x in range(200):
            # In Q1, mask coordinate is y * 200 + x
            if mask[y * 200 + x] == '0': # land
                if (x, y) not in blocked_coords:
                    q1_available_land.append((x, y))

    print(f"    Available land cells in Q1 for placement: {len(q1_available_land)}")

    # 6. Target quota per level for each resource type (halved again):
    # Lv 1~20: 30 tiles
    # Lv 21~25: 25 tiles
    # Lv 26~30: 20 tiles
    target_quota = {}
    for lv in range(1, 31):
        if lv <= 20:
            target_quota[lv] = 30
        elif lv <= 25:
            target_quota[lv] = 25
        else:
            target_quota[lv] = 20

    # Build the list of tiles to generate (subtracting occupied ones)
    tiles_to_generate = []
    for rtype in RESOURCES:
        for lv in range(1, 31):
            needed = target_quota[lv] - occupied_counts[rtype][lv]
            assert needed >= 0, f"Occupied count {occupied_counts[rtype][lv]} exceeds target quota {target_quota[lv]}"
            for _ in range(needed):
                tiles_to_generate.append((rtype, lv))

    total_new = len(tiles_to_generate)
    total_final = total_new + len(occupied_tiles)
    print(f"==> 6. Tiles to generate: {total_new} (Occupied preserved: {len(occupied_tiles)}, Total final: {total_final})")
    assert total_final == 3300, f"Expected total 3300, got {total_final}"
    assert len(q1_available_land) >= total_new, f"Not enough land cells: {len(q1_available_land)} < {total_new}"

    # 7. Randomly shuffle and assign coordinates
    random.seed(20261003)
    random.shuffle(q1_available_land)
    random.shuffle(tiles_to_generate)

    # 8. Batch insert into database
    print("==> 7. Inserting new resource wild tiles into Q1...")
    batch_size = 1000
    for i in range(0, total_new, batch_size):
        batch = tiles_to_generate[i:i + batch_size]
        coords = q1_available_land[i:i + len(batch)]
        values = []
        for (rtype, lv), (x, y) in zip(batch, coords):
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
        print(f"    Inserted {min(i + batch_size, total_new)} / {total_new}...")

    # 9. Verify database counts
    print("\n==> 8. Verifying counts by resource and level bracket in Q1:")
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
        WHERE x < 200 AND y < 200 AND type IN ('grainfield', 'ironworks', 'oil', 'rarefactory')
        GROUP BY type, bracket
        ORDER BY type, bracket;
    """]).decode().strip().splitlines()

    print(f"{'资源类型':<14} | {'等级区间':<10} | {'总数':<6} | {'等级数':<6} | {'最小储量':<10} | {'最大储量':<10}")
    print("-" * 65)
    for line in stats_raw:
        t, b, cnt, lvcnt, minr, maxr = line.split()
        print(f"{t:<14} | {b:<10} | {cnt:<6} | {lvcnt:<6} | {minr:<10} | {maxr:<10}")

    print("\n==> Quadrant summary across entire world map:")
    summary = subprocess.check_output(['mysql', '-u', 'root', '-e', """
        SELECT 
            CASE 
                WHEN x < 200 AND y < 200 THEN '第一象限 (Q1 西北大陆)'
                WHEN x >= 200 AND y < 200 THEN '第二象限 (Q2 东北大陆)'
                WHEN x < 200 AND y >= 200 THEN '第三象限 (Q3 西南大陆)'
                ELSE '第四象限 (Q4 东南大陆)'
            END as quadrant,
            count(*) as total_tiles,
            sum(case when type in ('grainfield', 'ironworks', 'oil', 'rarefactory') then 1 else 0 end) as resource_tiles,
            min(level) as min_lv,
            max(level) as max_lv,
            min(total_res) as min_res,
            max(total_res) as max_res
        FROM wargame.wild_tiles
        GROUP BY quadrant;
    """]).decode()
    print(summary)

if __name__ == '__main__':
    main()
