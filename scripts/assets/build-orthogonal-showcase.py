import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")

# Load models
grain_rgba = Image.open(OUT_DIR / "wild-grainfield-v3-orthogonal-transparent.png")
oil_rgba = Image.open(OUT_DIR / "wild-oil-v3-orthogonal-transparent.png")
city_rgba = Image.open(ROOT / "frontend/img/cities/player-city-lv10.png")

# Terrain base colors
GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

CELL = 260
PAD = 24
TOP_H = 100
LABEL_H = 46

# 4 columns:
# Col 0: 粮田 (草坪)
# Col 1: 粮田 (黑土地)
# Col 2: 油田 (草坪)
# Col 3: 油田 (黑土地)
# Extra Col 4: 玩家城市基准 (草坪) 对比

COLS = 5
IMG_W = PAD * (COLS + 1) + CELL * COLS
IMG_H = TOP_H + (CELL + LABEL_H + PAD) + 260 + PAD

canvas = Image.new("RGB", (IMG_W, IMG_H), (20, 24, 30))
draw = ImageDraw.Draw(canvas)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 26)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
font_col = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_detail = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 13)
font_sec = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 18)

# Title Header
draw.text((PAD, 18), "全新设计大地图微缩模型：【正向0度 · 纯透明落地 · 严整契合网格】实机效果", fill=(245, 248, 252), font=font_title)
draw.text((PAD, 56), "彻底摒弃45°菱形与垄沟壑 · 四角对应地图方格四角 · 前沿水平平行网格线 · 麦田成片稀疏自然 · 内含微缩收割农民", fill=(170, 190, 205), font=font_sub)

# Col configs: (title, bg_color, model_img, is_city)
items = [
    ("粮田 · 草坪嵌入", GRASS, grain_rgba, "成片金麦/无沟壑/割麦农民"),
    ("粮田 · 黑土嵌入", SOIL, grain_rgba, "成片金麦/无沟壑/割麦农民"),
    ("油田C · 草坪嵌入", GRASS, oil_rgba, "款型C抽油机/储油罐/发电机"),
    ("油田C · 黑土嵌入", SOIL, oil_rgba, "款型C抽油机/储油罐/发电机"),
    ("玩家城市 · 0度对照", GRASS, city_rgba, "大地图0度网格基准标杆"),
]

row_y = TOP_H + 10

for idx, (title, bg_col, model, desc) in enumerate(items):
    x = PAD + idx * (CELL + PAD)
    y = row_y
    
    # 1. Base terrain cell
    cell = Image.new("RGBA", (CELL, CELL), (*bg_col, 255))
    
    # Add subtle ground noise
    c_arr = np.array(cell)
    np.random.seed(100 + idx)
    noise = np.random.randint(-8, 8, c_arr[:, :, :3].shape)
    c_arr[:, :, :3] = np.clip(c_arr[:, :, :3] + noise, 0, 255)
    cell = Image.fromarray(c_arr)
    
    # 2. Composite model
    scaled_model = model.resize((CELL - 8, CELL - 8), Image.Resampling.LANCZOS)
    cell.alpha_composite(scaled_model, (4, 4))
    
    canvas.paste(cell.convert("RGB"), (x, y))
    
    # 3. Draw map grid line overlay (cyan dashed/solid)
    grid_color = (0, 220, 255) if idx == 4 else (255, 215, 0) if idx < 2 else (70, 160, 255)
    draw.rectangle([x, y, x + CELL, y + CELL], outline=grid_color, width=2)
    
    # 4. Corner ticks demonstrating 4-corner correspondence
    tick_len = 12
    draw.line([x, y, x + tick_len, y], fill=(255, 255, 255), width=3)
    draw.line([x, y, x, y + tick_len], fill=(255, 255, 255), width=3)
    
    draw.line([x + CELL, y, x + CELL - tick_len, y], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y, x + CELL, y + tick_len], fill=(255, 255, 255), width=3)
    
    draw.line([x, y + CELL, x + tick_len, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x, y + CELL, x, y + CELL - tick_len], fill=(255, 255, 255), width=3)
    
    draw.line([x + CELL, y + CELL, x + CELL - tick_len, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y + CELL, x + CELL, y + CELL - tick_len], fill=(255, 255, 255), width=3)
    
    # Labels
    draw.text((x + 4, y + CELL + 8), title, fill=(240, 245, 250), font=font_col)
    draw.text((x + 4, y + CELL + 28), desc, fill=(160, 180, 200), font=font_detail)

# Row 2: Detail inspection zoom-in panels
sec2_y = row_y + CELL + LABEL_H + PAD - 8
draw.text((PAD, sec2_y), "【核心微观与几何改进特写】", fill=(255, 220, 100), font=font_sec)

# 4 Zoom cards:
# Card 1: 粮田收割农民特写 (Zoom on the farmers)
# Card 2: 成片无垄沟金麦质感特写 (Zoom on the smooth wheat)
# Card 3: 粮田前沿水平接地平直度检验 (Zoom on bottom horizontal edge)
# Card 4: 油田款型C重工抽油机与储油罐特写 (Zoom on pumpjack & tank)

CARD_W = int((IMG_W - PAD * 5) / 4)
CARD_H = 190
card_y = sec2_y + 30

# Card 1: Farmers
c1_img = Image.open(OUT_DIR / "inspect_farmers_in_384.png").resize((CARD_W, CARD_H - 36), Image.Resampling.LANCZOS)
canvas.paste(c1_img, (PAD, card_y))
draw.rectangle([PAD, card_y, PAD + CARD_W, card_y + CARD_H - 36], outline=(255, 215, 0), width=2)
draw.text((PAD + 6, card_y + CARD_H - 30), "① 微缩农民收割/捆扎/运粮", fill=(255, 230, 120), font=font_detail)
draw.text((PAD + 6, card_y + CARD_H - 14), "草帽割麦/麦捆搬运/农舍水井", fill=(170, 185, 200), font=font_detail)

# Card 2: Smooth Wheat Texture
c2_img = Image.open(OUT_DIR / "wheat_zoom.png").resize((CARD_W, CARD_H - 36), Image.Resampling.LANCZOS)
canvas.paste(c2_img, (PAD * 2 + CARD_W, card_y))
draw.rectangle([PAD * 2 + CARD_W, card_y, PAD * 2 + CARD_W * 2, card_y + CARD_H - 36], outline=(255, 215, 0), width=2)
draw.text((PAD * 2 + CARD_W + 6, card_y + CARD_H - 30), "② 成片金黄麦田 (彻底消除沟壑)", fill=(255, 230, 120), font=font_detail)
draw.text((PAD * 2 + CARD_W + 6, card_y + CARD_H - 14), "无粗大弧垄/微观稀疏通透自然", fill=(170, 185, 200), font=font_detail)

# Card 3: Horizontal Base Edge
c3_img = Image.open(OUT_DIR / "slice_bottom.png").resize((CARD_W, CARD_H - 36), Image.Resampling.LANCZOS)
canvas.paste(c3_img, (PAD * 3 + CARD_W * 2, card_y))
draw.rectangle([PAD * 3 + CARD_W * 2, card_y, PAD * 3 + CARD_W * 3, card_y + CARD_H - 36], outline=(100, 220, 255), width=2)
draw.text((PAD * 3 + CARD_W * 2 + 6, card_y + CARD_H - 30), "③ 正向0度水平前沿 (严整平行网格)", fill=(100, 230, 255), font=font_detail)
draw.text((PAD * 3 + CARD_W * 2 + 6, card_y + CARD_H - 14), "严丝合缝对齐网格线/杜绝菱形角", fill=(170, 185, 200), font=font_detail)

# Card 4: Oilfield C
c4_img = oil_rgba.crop((30, 120, 354, 340)).resize((CARD_W, CARD_H - 36), Image.Resampling.LANCZOS)
canvas.paste(c4_img.convert("RGB"), (PAD * 4 + CARD_W * 3, card_y))
draw.rectangle([PAD * 4 + CARD_W * 3, card_y, PAD * 4 + CARD_W * 4, card_y + CARD_H - 36], outline=(70, 160, 255), width=2)
draw.text((PAD * 4 + CARD_W * 3 + 6, card_y + CARD_H - 30), "④ 油田款型C (重型单体大行程抽油机)", fill=(120, 190, 255), font=font_detail)
draw.text((PAD * 4 + CARD_W * 3 + 6, card_y + CARD_H - 14), "铆接储油罐/柴油机棚/管网阀门", fill=(170, 185, 200), font=font_detail)

showcase_path = OUT_DIR / "orthogonal_resources_v3_showcase.png"
canvas.save(showcase_path)
canvas.save(ARTIFACT_DIR / "orthogonal_resources_v3_showcase.png")
print("Saved showcase:", showcase_path)

