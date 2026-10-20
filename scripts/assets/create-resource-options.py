#!/usr/bin/env python3
"""Generate square-filling design options for Farmland (粮田) and Oilfield (油田)."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
OUT_DIR.mkdir(parents=True, exist_ok=True)
TARGET_SIZE = 384

# ==========================================
# 1. FARMLAND (粮田) 3 OPTIONS
# ==========================================

# Option A: 规整水网金稻方阵 (grainfield_option_one)
raw_grain_a = Image.open(OUT_DIR / "grainfield_option_one_1792391934850.jpg")
grain_a = raw_grain_a.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_a.save(OUT_DIR / "grainfield_option_a_neat_paddy.png")

# Option B: 丰收生态水稻农庄 (grainfield_option_two)
raw_grain_b = Image.open(OUT_DIR / "grainfield_option_two_1792391963423.jpg")
# Crop tiny 8px border (8, 7, 1015, 1014) to be 100% edge-to-edge
grain_b_cropped = raw_grain_b.crop((8, 7, 1015, 1014))
grain_b = grain_b_cropped.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_b.save(OUT_DIR / "grainfield_option_b_harvest_farm.png")

# Option C: 梯级精耕水稻田园 (from c-industrial/farm.png with full square ground)
raw_farm_c = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/c-industrial/farm.png")
# The diorama is slightly inset. Let's crop to the square diorama base
# Bounding box of farm in c-industrial:
# Let's crop the active diorama footprint (18, 120, 1236, 1230)
farm_c_crop = raw_farm_c.crop((25, 130, 1228, 1230))
grain_c = farm_c_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
grain_c.save(OUT_DIR / "grainfield_option_c_terraced_compound.png")

# ==========================================
# 2. OILFIELD (油田) 3 OPTIONS
# ==========================================

# Option A: 重工石化综合基地 (c-industrial/oilfield.png with full square ground)
raw_oil_a = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/c-industrial/oilfield.png")
# Crop the main diorama tightly to fill the square footprint
# The building & pumpjack & tank occupy from x=40..1215, y=140..1215
oil_a_crop = raw_oil_a.crop((45, 145, 1210, 1210))
oil_a = oil_a_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
oil_a.save(OUT_DIR / "oilfield_option_a_heavy_refinery.png")

# Option B: 深井开采与双雄巨罐基地 (wild-oil-v3-orthogonal.png with full square ground)
# v3-orthogonal has high derrick, twin tanks, pumpjack on transparent bg.
# Let's place it onto a full square industrial dirt/oil-stained ground base that fills the 384x384 square!
raw_oil_b = Image.open(ROOT / "output/imagegen/map-icons/wild-oil-v3-orthogonal.png").convert("RGBA")
# Create square ground base (dark compacted oil soil & gravel)
ground_b = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (82, 72, 58, 255))
# Add subtle industrial soil texture & oil stains
draw_b = ImageDraw.Draw(ground_b)
import random
random.seed(42)
for y in range(TARGET_SIZE):
    for x in range(0, TARGET_SIZE, 4):
        noise = random.randint(-8, 8)
        c = (max(0, min(255, 82 + noise)), max(0, min(255, 72 + noise)), max(0, min(255, 58 + noise)), 255)
        draw_b.point((x, y), fill=c)
# Soften base
ground_b = ground_b.filter(ImageFilter.GaussianBlur(1))

# Composite v3-orthogonal centered and filling the base
# v3 is 384x384. Resize it slightly so structures fit nicely while filling footprint
oil_b_scaled = raw_oil_b.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
ground_b.alpha_composite(oil_b_scaled)
oil_b = ground_b.convert("RGB")
oil_b.save(OUT_DIR / "oilfield_option_b_derrick_twin_tanks.png")

# Option C: 前线野战采油站 (b-frontier/oilfield.png with full square ground)
raw_oil_c = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png")
oil_c_crop = raw_oil_c.crop((40, 110, 1215, 1210))
oil_c = oil_c_crop.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)
oil_c.save(OUT_DIR / "oilfield_option_c_frontier_depot.png")

print("Generated all 6 individual options.")

# ==========================================
# 3. BUILD COMBINED COMPARISON SHOWCASE SHEET
# ==========================================
# Layout: 2 rows (Row 1: Farmland A/B/C, Row 2: Oilfield A/B/C)
CARD_W = TARGET_SIZE
CARD_H = TARGET_SIZE
PADDING = 24
HEADER_H = 80
TITLE_H = 40
CANVAS_W = PADDING * 4 + CARD_W * 3
CANVAS_H = HEADER_H + (CARD_H + TITLE_H + PADDING) * 2 + PADDING

showcase = Image.new("RGB", (CANVAS_W, CANVAS_H), (24, 28, 34))
draw = ImageDraw.Draw(showcase)

# Try loading system font
font_title = None
font_sub = None
font_opt = None
try:
    font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 28)
    font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
    font_opt = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 18)
except Exception:
    font_title = ImageFont.load_default()
    font_sub = font_title
    font_opt = font_title

# Draw Main Header
draw.text((PADDING, 18), "大地图资源微缩模型重构设计方案对比（占满空格规范）", fill=(245, 248, 250), font=font_title)
draw.text((PADDING, 54), "告别边缘截断与锯齿涂抹 · 1:1 满格严整构图 · 粮田（金黄水稻 🌾） & 油田（高大立体抽油机+储油罐）", fill=(170, 185, 200), font=font_sub)

# Row 1: Farmland
row1_y = HEADER_H + 10
farmlands = [
    (grain_a, "款型 A：规整水网金稻方阵", "100% 满格纯金稻浪 · 矩形水网纵横 · 识别度极强"),
    (grain_b, "款型 B：丰收生态水稻农庄", "满格金稻田块 · 水车石磨坊 · 丰收稻草垛与运粮木车"),
    (grain_c, "款型 C：梯级精耕水稻田园", "阶梯水稻田 · 砖石粮仓与水闸 · 规整庄园石木护栏"),
]

for idx, (img, name, desc) in enumerate(farmlands):
    x = PADDING + idx * (CARD_W + PADDING)
    showcase.paste(img, (x, row1_y))
    # Border
    draw.rectangle([x-1, row1_y-1, x+CARD_W, row1_y+CARD_H], outline=(218, 165, 32), width=2)
    # Caption
    draw.text((x, row1_y + CARD_H + 8), name, fill=(255, 215, 0), font=font_opt)
    draw.text((x, row1_y + CARD_H + 30), desc, fill=(200, 200, 180), font=font_sub)

# Row 2: Oilfield
row2_y = row1_y + CARD_H + TITLE_H + PADDING + 30
oilfields = [
    (oil_a, "款型 A：重工石化综合基地", "高大钢构抽油机 · 巨型圆柱银灰储罐 · 红砖机房与立体管网"),
    (oil_b, "款型 B：深井开采与双雄巨罐", "高耸钻井桁架塔 · 双联重型储油罐 · 磕头抽油机与油洼"),
    (oil_c, "款型 C：前线野战采油站", "重型单体大抽油机 · 铆接大型储油罐 · 柴油发电机棚与木栏"),
]

for idx, (img, name, desc) in enumerate(oilfields):
    x = PADDING + idx * (CARD_W + PADDING)
    showcase.paste(img, (x, row2_y))
    # Border
    draw.rectangle([x-1, row2_y-1, x+CARD_W, row2_y+CARD_H], outline=(70, 130, 180), width=2)
    # Caption
    draw.text((x, row2_y + CARD_H + 8), name, fill=(100, 180, 255), font=font_opt)
    draw.text((x, row2_y + CARD_H + 30), desc, fill=(180, 200, 210), font=font_sub)

showcase_path = OUT_DIR / "resource_redesign_showcase.png"
showcase.save(showcase_path)
print(f"Saved showcase to {showcase_path}")
