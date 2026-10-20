#!/usr/bin/env python3
"""Refine resource redesign options and create in-map simulation preview."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
TARGET_SIZE = 384

# ==========================================
# 1. FARMLAND (粮田) OPTIONS
# ==========================================

# Option A: 规整水网金稻方阵
raw_grain_a = Image.open(OUT_DIR / "grainfield_option_one_1792391934850.jpg")
grain_a = raw_grain_a.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_a.save(OUT_DIR / "grainfield_option_a_neat_paddy.png")

# Option B: 丰收生态水稻农庄 (crop 8px white border to fill 100%)
raw_grain_b = Image.open(OUT_DIR / "grainfield_option_two_1792391963423.jpg")
grain_b_cropped = raw_grain_b.crop((8, 7, 1015, 1014))
grain_b = grain_b_cropped.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_b.save(OUT_DIR / "grainfield_option_b_harvest_farm.png")

# Option C: 黄金水稻满格田垄 (cropped to full-tile rice & farm)
raw_farm_c = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/c-industrial/farm.png")
# Zoom crop to fill the square completely with the golden rice paddies and stone farmhouse
grain_c_crop = raw_farm_c.crop((120, 220, 1150, 1150))
grain_c = grain_c_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_c.save(OUT_DIR / "grainfield_option_c_terraced_compound.png")

# ==========================================
# 2. OILFIELD (油田) OPTIONS
# ==========================================

# Option A: 重工石化综合基地 (c-industrial/oilfield.png)
raw_oil_a = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/c-industrial/oilfield.png")
# Zoom crop to the core industrial plant so pumpjack and massive tank dominate and fill the tile
oil_a_crop = raw_oil_a.crop((140, 150, 1140, 1150))
oil_a = oil_a_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
oil_a.save(OUT_DIR / "oilfield_option_a_heavy_refinery.png")

# Option B: 深井开采与双雄巨罐基地 (wild-oil-v3-orthogonal.png)
# Zoom crop to structures and dark oil mud ground
raw_oil_b = Image.open(ROOT / "output/imagegen/map-icons/wild-oil-v3-orthogonal.png").convert("RGBA")
# Base with industrial ground
ground_b = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (78, 68, 54, 255))
oil_b_scaled = raw_oil_b.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
ground_b.alpha_composite(oil_b_scaled)
oil_b = ground_b.convert("RGB")
oil_b.save(OUT_DIR / "oilfield_option_b_derrick_twin_tanks.png")

# Option C: 前线野战采油站 (b-frontier/oilfield.png)
raw_oil_c = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png")
oil_c_crop = raw_oil_c.crop((120, 120, 1140, 1140))
oil_c = oil_c_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
oil_c.save(OUT_DIR / "oilfield_option_c_frontier_depot.png")

# ==========================================
# 3. BUILD POLISHED SHOWCASE COMPARISON
# ==========================================
CARD_W = TARGET_SIZE
CARD_H = TARGET_SIZE
PADDING = 28
HEADER_H = 88
TITLE_H = 46
CANVAS_W = PADDING * 4 + CARD_W * 3
CANVAS_H = HEADER_H + (CARD_H + TITLE_H + PADDING) * 2 + PADDING + 10

showcase = Image.new("RGB", (CANVAS_W, CANVAS_H), (20, 24, 30))
draw = ImageDraw.Draw(showcase)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 28)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
font_opt = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 19)
font_desc = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)

# Draw Main Header
draw.text((PADDING, 18), "大地图资源微缩模型重构设计方案总览（占满空格规范）", fill=(245, 248, 250), font=font_title)
draw.text((PADDING, 56), "告别边缘截断与锯齿涂抹 · 1:1 满格严整构图 · 粮田（金黄水稻 🌾） & 油田（高大立体抽油机+储油罐）", fill=(170, 185, 200), font=font_sub)

# Row 1: Farmland
row1_y = HEADER_H + 10
farmlands = [
    (grain_a, "粮田 款型 A：规整水网金稻方阵", "100% 满格纯金稻浪 · 矩形水网纵横 · 纯粹金色"),
    (grain_b, "粮田 款型 B：丰收生态水稻农庄", "满格金黄稻田 · 石基水车磨坊 · 丰收稻草垛与运粮木车"),
    (grain_c, "粮田 款型 C：梯级精耕水稻田园", "阶梯水稻田 · 砖石粮仓与水闸 · 规整庄园石木护栏"),
]

for idx, (img, name, desc) in enumerate(farmlands):
    x = PADDING + idx * (CARD_W + PADDING)
    showcase.paste(img, (x, row1_y))
    draw.rectangle([x-2, row1_y-2, x+CARD_W+1, row1_y+CARD_H+1], outline=(225, 175, 40), width=2)
    draw.text((x, row1_y + CARD_H + 8), name, fill=(255, 215, 0), font=font_opt)
    draw.text((x, row1_y + CARD_H + 32), desc, fill=(200, 200, 180), font=font_desc)

# Row 2: Oilfield
row2_y = row1_y + CARD_H + TITLE_H + PADDING + 20
oilfields = [
    (oil_a, "油田 款型 A：重工石化综合基地", "高大钢构抽油机 · 巨型圆柱银灰储罐 · 红砖机房与立体管网"),
    (oil_b, "油田 款型 B：深井开采与双雄巨罐", "高耸钻井桁架塔 · 双联重型储油罐 · 磕头抽油机与油洼"),
    (oil_c, "油田 款型 C：前线野战采油站", "重型单体大抽油机 · 铆接大型储油罐 · 柴油发电机棚与木栏"),
]

for idx, (img, name, desc) in enumerate(oilfields):
    x = PADDING + idx * (CARD_W + PADDING)
    showcase.paste(img, (x, row2_y))
    draw.rectangle([x-2, row2_y-2, x+CARD_W+1, row2_y+CARD_H+1], outline=(70, 140, 210), width=2)
    draw.text((x, row2_y + CARD_H + 8), name, fill=(110, 190, 255), font=font_opt)
    draw.text((x, row2_y + CARD_H + 32), desc, fill=(180, 205, 220), font=font_desc)

showcase_path = OUT_DIR / "resource_redesign_showcase.png"
showcase.save(showcase_path)
print(f"Updated showcase to {showcase_path}")

# ==========================================
# 4. BUILD IN-MAP SIMULATION PREVIEW
# ==========================================
# Simulate a 3x3 game map view with grassland, black soil, and the redesigned tiles
GRID_CELL = 240
MAP_W = GRID_CELL * 4
MAP_H = GRID_CELL * 2 + 60

map_sim = Image.new("RGB", (MAP_W, MAP_H), (30, 35, 40))
map_draw = ImageDraw.Draw(map_sim)

# Title
map_draw.text((20, 15), "实机大地图嵌入效果模拟（黑土地 / 草坪地貌环境）", fill=(245, 248, 250), font=font_title)

# Draw ground terrain background
# Left 2 cells: Olive grassland (104, 103, 71); Right 2 cells: Dark soil (92, 81, 62)
for gy in range(2):
    for gx in range(4):
        cx = gx * GRID_CELL
        cy = 60 + gy * GRID_CELL
        bg_col = (104, 103, 71) if (gx + gy) % 2 == 0 else (92, 81, 62)
        map_draw.rectangle([cx, cy, cx + GRID_CELL - 1, cy + GRID_CELL - 1], fill=bg_col, outline=(40, 50, 45), width=1)

# Place 4 demo tiles:
# (0, 0) Grainfield Option A
map_sim.paste(grain_a.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (2, 62))
map_draw.rectangle([2, 62, GRID_CELL - 2, 60 + GRID_CELL - 2], outline=(255, 215, 0), width=2)
map_draw.text((10, 68), "粮田A (水网金稻)", fill=(255, 255, 255), font=font_desc)

# (1, 0) Grainfield Option B
map_sim.paste(grain_b.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (GRID_CELL + 2, 62))
map_draw.rectangle([GRID_CELL + 2, 62, GRID_CELL * 2 - 2, 60 + GRID_CELL - 2], outline=(255, 215, 0), width=2)
map_draw.text((GRID_CELL + 10, 68), "粮田B (水车农庄)", fill=(255, 255, 255), font=font_desc)

# (2, 0) Oilfield Option A
map_sim.paste(oil_a.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (GRID_CELL * 2 + 2, 62))
map_draw.rectangle([GRID_CELL * 2 + 2, 62, GRID_CELL * 3 - 2, 60 + GRID_CELL - 2], outline=(70, 140, 210), width=2)
map_draw.text((GRID_CELL * 2 + 10, 68), "油田A (重工石化基地)", fill=(255, 255, 255), font=font_desc)

# (3, 0) Oilfield Option B
map_sim.paste(oil_b.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (GRID_CELL * 3 + 2, 62))
map_draw.rectangle([GRID_CELL * 3 + 2, 62, GRID_CELL * 4 - 2, 60 + GRID_CELL - 2], outline=(70, 140, 210), width=2)
map_draw.text((GRID_CELL * 3 + 10, 68), "油田B (深井双罐基地)", fill=(255, 255, 255), font=font_desc)

# Row 2:
# (0, 1) Grainfield Option C
map_sim.paste(grain_c.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (2, 60 + GRID_CELL + 2))
map_draw.rectangle([2, 60 + GRID_CELL + 2, GRID_CELL - 2, 60 + GRID_CELL * 2 - 2], outline=(255, 215, 0), width=2)
map_draw.text((10, 60 + GRID_CELL + 8), "粮田C (梯级水稻庄园)", fill=(255, 255, 255), font=font_desc)

# (1, 1) Oilfield Option C
map_sim.paste(oil_c.resize((GRID_CELL - 4, GRID_CELL - 4), Image.Resampling.LANCZOS), (GRID_CELL + 2, 60 + GRID_CELL + 2))
map_draw.rectangle([GRID_CELL + 2, 60 + GRID_CELL + 2, GRID_CELL * 2 - 2, 60 + GRID_CELL * 2 - 2], outline=(70, 140, 210), width=2)
map_draw.text((GRID_CELL + 10, 60 + GRID_CELL + 8), "油田C (前线野战采油)", fill=(255, 255, 255), font=font_desc)

# (2, 1) and (3, 1): empty terrain showing grid contrast
map_draw.text((GRID_CELL * 2 + 30, 60 + GRID_CELL + 90), "大地图草坪原生底色", fill=(230, 240, 220), font=font_desc)
map_draw.text((GRID_CELL * 3 + 30, 60 + GRID_CELL + 90), "大地图黑土地原生底色", fill=(230, 220, 200), font=font_desc)

map_sim_path = OUT_DIR / "resource_map_embedded_preview.png"
map_sim.save(map_sim_path)
print(f"Saved map simulation to {map_sim_path}")
