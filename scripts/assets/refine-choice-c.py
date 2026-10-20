import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
TARGET_SIZE = 384

# ====================================================
# 1. REFINE OILFIELD C (Clean up studio shadow on right)
# ====================================================
oil_orig = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png").convert("RGBA")
o_img = cv2.imread(str(ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png"))
h, w = o_img.shape[:2]

corners = np.vstack([
    o_img[:30, :30].reshape(-1, 3),
    o_img[:30, -30:].reshape(-1, 3),
    o_img[-30:, :30].reshape(-1, 3),
    o_img[-30:, -30:].reshape(-1, 3)
])
bg_mean = np.mean(corners, axis=0)
diff = np.linalg.norm(o_img.astype(float) - bg_mean, axis=2)

# Flood fill outer
is_bg = (diff < 22).astype(np.uint8)
for y in range(0, h, 20):
    for x in [0, w - 1]:
        if is_bg[y, x] == 1:
            cv2.floodFill(is_bg, None, (x, y), 2)
for x in range(0, w, 20):
    for y in [0, h - 1]:
        if is_bg[y, x] == 1:
            cv2.floodFill(is_bg, None, (x, y), 2)

hsv = cv2.cvtColor(o_img, cv2.COLOR_BGR2HSV)
sat = hsv[:, :, 1]
is_inner_bg = (diff < 18) & (sat < 20)

final_bg = (is_bg == 2) | is_inner_bg

# Clean up cast shadow on the far right (x > 1100, y < 680) that sits outside the tree/tank
# The tree right edge is at x=1150, tank is at x=1030
for y in range(400, 680):
    for x in range(1120, w):
        # if pixel is grey shadow (low saturation, low variance)
        if sat[y, x] < 30 and o_img[y, x, 0] > 100:
            final_bg[y, x] = True

fg_mask = (~final_bg).astype(np.uint8) * 255
fg_mask = cv2.GaussianBlur(fg_mask, (3, 3), 0)

b, g, r = cv2.split(o_img)
rgba_oil = cv2.merge([r, g, b, fg_mask])
oil_clean = Image.fromarray(rgba_oil)

# Crop bbox
o_arr = np.array(oil_clean)
y_o, x_o = np.where(o_arr[:, :, 3] > 10)
crop_o = oil_clean.crop((x_o.min() - 8, y_o.min() - 8, x_o.max() + 8, y_o.max() + 8))
oil_fg = crop_o.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)

# ====================================================
# 2. REFINE FARMLAND C (Clean cut)
# ====================================================
farm_clean = Image.open(OUT_DIR / "farm_clean_test.png").convert("RGBA")
f_arr = np.array(farm_clean)
y_f, x_f = np.where(f_arr[:, :, 3] > 10)
crop_f = farm_clean.crop((x_f.min() - 8, y_f.min() - 8, x_f.max() + 8, y_f.max() + 8))
farm_fg = crop_f.resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)

# ====================================================
# 3. FULL SQUARE VERSION (铺满正方形)
# ====================================================
# Farm Full Square (Rich agricultural soil & grass base)
np.random.seed(101)
farm_base = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (90, 80, 58, 255))
fb_arr = np.array(farm_base)
noise_f = np.random.randint(-10, 10, fb_arr[:, :, :3].shape)
fb_arr[:, :, :3] = np.clip(fb_arr[:, :, :3] + noise_f, 0, 255)
for y in range(TARGET_SIZE):
    for x in range(TARGET_SIZE):
        d = min(x, y, TARGET_SIZE - 1 - x, TARGET_SIZE - 1 - y)
        if d < 35:
            fb_arr[y, x, 1] = min(255, fb_arr[y, x, 1] + 12)
farm_base = Image.fromarray(fb_arr).filter(ImageFilter.GaussianBlur(1))

farm_full_square = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE))
farm_full_square.alpha_composite(farm_base)
farm_full_square.alpha_composite(farm_fg)
farm_full_square_rgb = farm_full_square.convert("RGB")
farm_full_square_rgb.save(OUT_DIR / "grainfield_c_full_square.png")
farm_full_square_rgb.save(ROOT / "frontend/img/map/wild-grainfield-square-c.png")

# Oil Full Square (Industrial gravel & dirt base)
oil_base = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (96, 84, 66, 255))
ob_arr = np.array(oil_base)
noise_o = np.random.randint(-12, 12, ob_arr[:, :, :3].shape)
ob_arr[:, :, :3] = np.clip(ob_arr[:, :, :3] + noise_o, 0, 255)
for y in range(TARGET_SIZE):
    for x in range(TARGET_SIZE):
        dist_c = np.sqrt((x - 192)**2 + (y - 240)**2)
        if dist_c < 90:
            ob_arr[y, x, :3] = np.clip(ob_arr[y, x, :3].astype(int) - 15, 0, 255)
oil_base = Image.fromarray(ob_arr).filter(ImageFilter.GaussianBlur(1))

oil_full_square = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE))
oil_full_square.alpha_composite(oil_base)
oil_full_square.alpha_composite(oil_fg)
oil_full_square_rgb = oil_full_square.convert("RGB")
oil_full_square_rgb.save(OUT_DIR / "oilfield_c_full_square.png")
oil_full_square_rgb.save(ROOT / "frontend/img/map/wild-oil-square-c.png")

# ====================================================
# 4. PURE TRANSPARENT GROUNDED VERSION (纯透明落地)
# ====================================================
farm_shadow = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
fs_draw = ImageDraw.Draw(farm_shadow)
fs_draw.polygon([
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.16),
    (TARGET_SIZE * 0.96, TARGET_SIZE * 0.50),
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.94),
    (TARGET_SIZE * 0.04, TARGET_SIZE * 0.52)
], fill=(16, 12, 8, 175))
farm_shadow = farm_shadow.filter(ImageFilter.GaussianBlur(14))

farm_transparent = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
farm_transparent.alpha_composite(farm_shadow)
farm_transparent.alpha_composite(farm_fg)
farm_transparent.save(OUT_DIR / "grainfield_c_transparent.png")
farm_transparent.save(ROOT / "frontend/img/map/wild-grainfield-transparent-c.png")

oil_shadow = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
os_draw = ImageDraw.Draw(oil_shadow)
os_draw.polygon([
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.18),
    (TARGET_SIZE * 0.95, TARGET_SIZE * 0.52),
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.93),
    (TARGET_SIZE * 0.05, TARGET_SIZE * 0.50)
], fill=(16, 12, 8, 185))
oil_shadow = oil_shadow.filter(ImageFilter.GaussianBlur(14))

oil_transparent = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
oil_transparent.alpha_composite(oil_shadow)
oil_transparent.alpha_composite(oil_fg)
oil_transparent.save(OUT_DIR / "oilfield_c_transparent.png")
oil_transparent.save(ROOT / "frontend/img/map/wild-oil-transparent-c.png")

# ====================================================
# 5. REGENERATE POLISHED MAP EMBEDDED SHEET
# ====================================================
CELL = 260
PAD = 20
TOP_H = 84
LABEL_H = 34

IMG_W = PAD * 5 + CELL * 4
IMG_H = TOP_H + (CELL + LABEL_H + PAD) * 2 + PAD

canvas = Image.new("RGB", (IMG_W, IMG_H), (22, 26, 32))
draw = ImageDraw.Draw(canvas)

font_main = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 26)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
font_col = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 17)
font_cell = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 13)

draw.text((PAD, 18), "款型 C 实机大地图嵌入效果对比：【满格正方形】 VS 【纯透明落地】", fill=(245, 248, 250), font=font_main)
draw.text((PAD, 54), "展示在真实草坪（橄榄绿）与黑土地（深褐色）环境下的融合表现 · 对比是否存在矩形边界割裂感", fill=(175, 190, 205), font=font_sub)

GRASS = (104, 103, 71)
BLACK_SOIL = (92, 81, 62)

col_configs = [
    ("粮田款型 C · 草坪", GRASS, (225, 175, 40)),
    ("粮田款型 C · 黑土", BLACK_SOIL, (225, 175, 40)),
    ("油田款型 C · 草坪", GRASS, (70, 140, 220)),
    ("油田款型 C · 黑土", BLACK_SOIL, (70, 140, 220)),
]

# Row 1: 铺满正方形格子
row1_y = TOP_H + 10
draw.text((PAD, row1_y - 2), "【展示一：铺满正方形格子】（全格填满泥土/工业基座，呈严整正方形网格）", fill=(255, 215, 0), font=font_col)

for idx, (col_title, bg_color, border_col) in enumerate(col_configs):
    x = PAD + idx * (CELL + PAD)
    y = row1_y + 24
    
    cell_bg = Image.new("RGBA", (CELL, CELL), (*bg_color, 255))
    if idx < 2:
        target = farm_full_square_rgb.resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)
    else:
        target = oil_full_square_rgb.resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)
        
    cell_bg.paste(target, (2, 2))
    canvas.paste(cell_bg, (x, y))
    draw.rectangle([x, y, x + CELL, y + CELL], outline=border_col, width=2)
    caption = "铺满方格 · " + ("草坪" if idx % 2 == 0 else "黑土")
    draw.text((x + 8, y + CELL + 6), caption, fill=(210, 220, 230), font=font_cell)

# Row 2: 方案一纯透明落地
row2_y = row1_y + 24 + CELL + LABEL_H + PAD
draw.text((PAD, row2_y - 2), "【展示二：纯透明落地模型】（背景完全透明+真实接地软阴影，沙盘战棋微缩模型直接立于地表）", fill=(100, 210, 255), font=font_col)

for idx, (col_title, bg_color, border_col) in enumerate(col_configs):
    x = PAD + idx * (CELL + PAD)
    y = row2_y + 24
    
    cell_bg = Image.new("RGBA", (CELL, CELL), (*bg_color, 255))
    if idx < 2:
        target_trans = farm_transparent.resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)
    else:
        target_trans = oil_transparent.resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)
        
    cell_bg.alpha_composite(target_trans, (2, 2))
    canvas.paste(cell_bg.convert("RGB"), (x, y))
    draw.rectangle([x, y, x + CELL, y + CELL], outline=(50, 160, 200), width=2)
    caption = "纯透明落地 · " + ("草坪" if idx % 2 == 0 else "黑土")
    draw.text((x + 8, y + CELL + 6), caption, fill=(180, 225, 240), font=font_cell)

comparison_path = OUT_DIR / "choice_c_square_vs_transparent_comparison.png"
canvas.save(comparison_path)
canvas.save(ROOT / "output/choice_c_square_vs_transparent_comparison.png")
print("Refined showcase complete.")
