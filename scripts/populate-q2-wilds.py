#!/usr/bin/env python3
"""
Populate Quadrant 2 (X: 200~399, Y: 0~199) with resource wild tiles and update resource reserves.
Requirements:
1. Resource amount enlarged 200x for Q2: level * 160000 (Lv 8 = 1,280,000)
2. Resource wild tile level range: 1 to 30
3. Quadrant 2 count per level for each resource type:
   - Lv 1~20: 120 tiles per level
   - Lv 21~25: 100 tiles per level
   - Lv 26~30: 80 tiles per level
   Total per type: 3,300; 4 types = 13,200 tiles.
4. Quadrants 1, 3, 4 are NOT developed (Q1 keeps original baseline, Q3 & Q4 remain untouched).
5. In Q2, wild tiles are randomly distributed with a strong trend:
   The closer to the ocean, the higher the level!
"""

import json
import random
import subprocess
import sys
from collections import deque

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
    print("==> Fetching world map terrain mask...")
    mask = subprocess.check_output(['mysql', '-u', 'root', '-N', '-e', 'SELECT terrain_data FROM wargame.world_map WHERE id=1;']).decode().strip()
    assert len(mask) == 40000, f"Unexpected terrain mask length: {len(mask)}"

    # 1. Update world_map size to 400
    print("==> Updating world_map size to 400...")
    run_sql("UPDATE world_map SET size = 400 WHERE id = 1;")

    # 2. Quadrants 1, 3, 4 are NOT developed.
    # Restore Q1's original resource tiles to their baseline reserves (level * 800)
    print("==> Preserving Q1 original baseline reserves (level * 800) since Q1 is not developed...")
    run_sql("UPDATE wild_tiles SET total_res = level * 800 WHERE world_id = 1 AND x < 200 AND type IN ('grainfield', 'ironworks', 'oil', 'rarefactory');")

    # 3. Collect valid land coordinates and sea cells in Quadrant 2 (X in [200, 399], Y in [0, 199])
    # Note: Q2 is mirrored horizontally (flipX): lx = 399 - x, ly = y
    q2_sea = set()
    q2_land = []
    for y in range(200):
        for x in range(200, 400):
            lx = 399 - x
            ly = y
            if mask[ly * 200 + lx] == '1':
                q2_sea.add((x, y))
            else:
                q2_land.append((x, y))

    print(f"==> Total land coordinates in Q2: {len(q2_land)}, sea coordinates in Q2: {len(q2_sea)}")
    assert len(q2_land) >= 13200, f"Not enough land coordinates: {len(q2_land)} < 13200"

    # 4. Multi-source BFS to compute shortest distance to ocean for all Q2 land cells
    print("==> Computing ocean distance field via BFS...")
    dist = {}
    queue = deque()

    # Internal sea cells have distance 0
    for x, y in q2_sea:
        dist[(x, y)] = 0
        queue.append((x, y))

    # Western boundary (x=199 in Q1 central ocean):
    for y in range(200):
        if mask[y * 200 + 199] == '1':
            dist[(199, y)] = 0
            queue.append((199, y))

    # Southern boundary (y=200 in Q4 central ocean):
    for x in range(200, 400):
        lx = 399 - x
        if mask[199 * 200 + lx] == '1':
            dist[(x, 200)] = 0
            queue.append((x, 200))

    while queue:
        cx, cy = queue.popleft()
        d = dist[(cx, cy)]
        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nx, ny = cx + dx, cy + dy
            if 200 <= nx < 400 and 0 <= ny < 200:
                if (nx, ny) not in dist:
                    dist[(nx, ny)] = d + 1
                    queue.append((nx, ny))

    # 5. Sample 13,200 distinct land coordinates across Q2
    rng = random.Random(20261003)
    rng.shuffle(q2_land)
    chosen_coords = q2_land[:13200]

    # 6. Prepare items sorted from level 30 down to level 1
    types = ['grainfield', 'ironworks', 'oil', 'rarefactory']
    units = ['infantry', 'motor', 'armored', 'ltank']

    items = []
    # For each level from 30 down to 1:
    for lv in range(30, 0, -1):
        count = 80 if lv >= 26 else (100 if lv >= 21 else 120)
        # Create equal items for each type, but randomize type order at this level
        lv_items = []
        for t in types:
            for _ in range(count):
                lv_items.append((t, lv))
        rng.shuffle(lv_items)
        items.extend(lv_items)

    assert len(items) == 13200, f"Total items mismatch: {len(items)}"

    # 7. Rank coordinates according to ocean proximity:
    # "按照越靠近海洋等级越高的趋势 随机分布到地图中"
    # Closer to ocean (smaller d) -> higher score -> gets higher level!
    # Gaussian perturbation (sigma = 12) ensures smooth, natural geographical gradient without rigid lines
    sigma = 12.0
    scored_coords = []
    for x, y in chosen_coords:
        d = dist.get((x, y), 50)
        score = -d + rng.gauss(0, sigma)
        scored_coords.append((score, x, y, d))

    # Sort descending by score: highest score (closest to sea) first
    scored_coords.sort(key=lambda s: s[0], reverse=True)

    # 8. Clean existing Q2 wild tiles if any
    print("==> Cleaning existing Q2 wild tiles...")
    run_sql("DELETE FROM wild_tiles WHERE world_id = 1 AND x >= 200;")

    # 9. Insert the 13,200 wild tiles in batches
    print("==> Inserting 13,200 resource wild tiles into Q2...")
    batch_size = 1000
    for b_start in range(0, len(items), batch_size):
        b_end = min(b_start + batch_size, len(items))
        rows = []
        for i in range(b_start, b_end):
            t, lv = items[i]
            score, x, y, d = scored_coords[i]
            total_res = lv * 160000
            unit = rng.choice(units)
            garrison = json.dumps({unit: 5 * lv}, separators=(',', ':'))
            rows.append(f"(1, '{t}', {x}, {y}, {lv}, '{garrison}', 0, 0, NULL, {total_res}, 0, 0, 0, 'manual', 0)")

        sql = f"""
        INSERT INTO wild_tiles
        (world_id, type, x, y, level, garrison, scouted, occupied, occupied_by, total_res, mined, version, gathering, gather_mode, gather_city_slot)
        VALUES {', '.join(rows)};
        """
        run_sql(sql)
        print(f"    Inserted {b_end} / 13,200...")

    print("==> Verifying ocean distance by level bracket in Q2:")
    bracket_stats = {}
    for i in range(len(items)):
        t, lv = items[i]
        score, x, y, d = scored_coords[i]
        bracket = 'Lv.26~30 (最高级 · 近海)' if lv >= 26 else (
            'Lv.21~25 (高级 · 沿海)' if lv >= 21 else (
                'Lv.16~20 (中高级)' if lv >= 16 else (
                    'Lv.11~15 (中级)' if lv >= 11 else (
                        'Lv.6~10 (初中级)' if lv >= 6 else 'Lv.1~5 (基础级 · 内陆纵深)'
                    )
                )
            )
        )
        bracket_stats.setdefault(bracket, []).append(d)

    for b_name in ['Lv.26~30 (最高级 · 近海)', 'Lv.21~25 (高级 · 沿海)', 'Lv.16~20 (中高级)', 'Lv.11~15 (中级)', 'Lv.6~10 (初中级)', 'Lv.1~5 (基础级 · 内陆纵深)']:
        ds = bracket_stats[b_name]
        print(f"    {b_name:30s}: 数量={len(ds):4d}, 平均距海={sum(ds)/len(ds):5.1f}格, 最近={min(ds)}格, 最远={max(ds)}格")

    print("\n==> Quadrant summary in database:")
    res = subprocess.check_output([
        'mysql', '-u', 'root', 'wargame', '-e',
        """
        SELECT
            CASE
                WHEN x < 200 AND y < 200 THEN '第一象限 (Q1 未开发原样)'
                WHEN x >= 200 AND y < 200 THEN '第二象限 (Q2 高阶资源战区)'
                WHEN x < 200 AND y >= 200 THEN '第三象限 (Q3 未开发)'
                ELSE '第四象限 (Q4 未开发)'
            END AS quadrant,
            COUNT(*) AS total_tiles,
            MIN(level) AS min_lv,
            MAX(level) AS max_lv,
            MIN(total_res) AS min_res,
            MAX(total_res) AS max_res
        FROM wild_tiles
        WHERE world_id = 1
        GROUP BY quadrant;
        """
    ]).decode('utf-8')
    print(res)

if __name__ == '__main__':
    main()
