import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
OUT_DIR.mkdir(parents=True, exist_ok=True)
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")
TARGET = 384

def build_seamless_rice_model(raw_img_path, crop_box, target_w, out_png_path, shadow_offset_y=6):
    img = cv2.imread(str(raw_img_path))
    h, w = img.shape[:2]
    
    # Crop the diorama area (including a bit of cast shadow room)
    x0, y0, x1, y1 = crop_box
    pad = 15
    cx0 = max(0, x0 - pad)
    cy0 = max(0, y0 - pad)
    cx1 = min(w, x1 + pad)
    cy1 = min(h, y1 + pad)
    
    sub = img[cy0:cy1, cx0:cx1].copy()
    sh, sw = sub.shape[:2]
    
    # Isolate from pure white background
    diff = np.linalg.norm(sub.astype(float) - 255.0, axis=2)
    hsv = cv2.cvtColor(sub, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    val = hsv[:, :, 2]
    
    # Outer background mask (floodfill from borders)
    is_white = (diff < 18).astype(np.uint8)
    flood = is_white.copy()
    for y in range(0, sh, 10):
        for x in [0, sw - 1]:
            if flood[y, x] == 1:
                cv2.floodFill(flood, None, (x, y), 2)
    for x in range(0, sw, 10):
        for y in [0, sh - 1]:
            if flood[y, x] == 1:
                cv2.floodFill(flood, None, (x, y), 2)
                
    exterior = (flood == 2)
    
    # Alpha channel
    alpha = np.ones((sh, sw), dtype=np.float32)
    b, g, r = cv2.split(sub)
    
    for y in range(sh):
        for x in range(sw):
            if exterior[y, x]:
                # If it's a soft shadow (low saturation, val between 130 and 250)
                if sat[y, x] < 35 and 130 <= val[y, x] < 252:
                    shadow_alpha = (255.0 - val[y, x]) / 110.0
                    alpha[y, x] = max(0.0, min(0.60, shadow_alpha))
                    # replace white with neutral dark shadow color
                    r[y, x] = 16
                    g[y, x] = 12
                    b[y, x] = 8
                else:
                    alpha[y, x] = 0.0
                    
    alpha_uint8 = (alpha * 255.0).astype(np.uint8)
    alpha_uint8 = cv2.GaussianBlur(alpha_uint8, (3, 3), 0)
    
    rgba = cv2.merge([r, g, b, alpha_uint8])
    pil_sub = Image.fromarray(rgba)
    
    # Crop to actual content
    y_idx, x_idx = np.where(alpha_uint8 > 10)
    tight_crop = pil_sub.crop((x_idx.min(), y_idx.min(), x_idx.max(), y_idx.max()))
    
    # Scale to target_w inside 384x384 canvas
    # With target_w = 332, margin on left/right is (384 - 332)/2 = 26px!
    tw, th = tight_crop.size
    scale = target_w / tw
    scaled_w = int(tw * scale)
    scaled_h = int(th * scale)
    scaled_img = tight_crop.resize((scaled_w, scaled_h), Image.Resampling.LANCZOS)
    
    # Enhance golden vibrancy
    scaled_img = ImageEnhance.Color(scaled_img).enhance(1.12)
    
    # Position on 384x384 canvas
    canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
    paste_x = (TARGET - scaled_w) // 2
    paste_y = (TARGET - scaled_h) // 2 + shadow_offset_y
    
    canvas.alpha_composite(scaled_img, (paste_x, paste_y))
    canvas.save(out_png_path)
    print(f"Saved {out_png_path}, size={TARGET}x{TARGET}, content={scaled_w}x{scaled_h}, margin_x={paste_x}px, margin_y={paste_y}px")
    return canvas

# Process Variant 1: Stone waterway & sluice gate
v1_crop = (36, 185, 988, 855)
v1_model = build_seamless_rice_model(
    ARTIFACT_DIR / "ricefield_paddy_v1_1792423238204.jpg",
    v1_crop,
    target_w=330,
    out_png_path=OUT_DIR / "rice_model_v1_waterway.png",
    shadow_offset_y=4
)

# Process Variant 2: Harvest handcart & farmers
v2_crop = (52, 220, 982, 816)
v2_model = build_seamless_rice_model(
    ARTIFACT_DIR / "ricefield_paddy_v2_1792423271653.jpg",
    v2_crop,
    target_w=332,
    out_png_path=OUT_DIR / "rice_model_v2_cart.png",
    shadow_offset_y=8
)

# ==============================================================================
# BUILD DETAILED SIDE-BY-SIDE IN-MAP SHOWCASE
# ==============================================================================
GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

CELL = 260
PAD = 24
TOP_H = 105
ROW_H = CELL + 52
TOTAL_W = PAD * 5 + CELL * 4
TOTAL_H = TOP_H + ROW_H + PAD

canvas = Image.new("RGB", (TOTAL_W, TOTAL_H), (18, 22, 28))
draw = ImageDraw.Draw(canvas)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 24)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)
font_card = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_desc = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 12)

draw.text((PAD, 18), "全新设计粮田：【连成一片水稻 · 底座透明 · 留有间隔空间零截断】效果评审", fill=(245, 248, 252), font=font_title)
draw.text((PAD, 56), "四周留足 26px 呼吸间隔空间 · 彻底解决边缘半截水稻问题 · 纯透明底座自然接地 · 正向 0 度契合网格", fill=(255, 215, 0), font=font_sub)

cards = [
    ("款式一：【石渠水网·金稻方阵】", "嵌入大地图草坪 (橄榄金绿)", GRASS, v1_model, (255, 215, 0)),
    ("款式一：【石渠水网·金稻方阵】", "嵌入大地图黑土 (深棕褐色)", SOIL, v1_model, (255, 215, 0)),
    ("款式二：【丰收木车·金稻平原】", "嵌入大地图草坪 (橄榄金绿)", GRASS, v2_model, (100, 220, 255)),
    ("款式二：【丰收木车·金稻平原】", "嵌入大地图黑土 (深棕褐色)", SOIL, v2_model, (100, 220, 255)),
]

for i, (title, sub, bg_col, model, tag_col) in enumerate(cards):
    x = PAD + i * (CELL + PAD)
    y = TOP_H + 10
    
    # Terrain cell
    cell = Image.new("RGBA", (CELL, CELL), (*bg_col, 255))
    c_arr = np.array(cell)
    np.random.seed(600 + i)
    c_arr[:, :, :3] = np.clip(c_arr[:, :, :3] + np.random.randint(-7, 7, c_arr[:, :, :3].shape), 0, 255)
    cell = Image.fromarray(c_arr.astype(np.uint8))
    
    # Paste transparent model onto terrain
    scaled_m = model.resize((CELL, CELL), Image.Resampling.LANCZOS)
    cell.alpha_composite(scaled_m)
    
    canvas.paste(cell.convert("RGB"), (x, y))
    
    # Map grid border
    draw.rectangle([x, y, x + CELL, y + CELL], outline=tag_col, width=2)
    
    # Corner alignment crosshairs
    tlen = 14
    draw.line([x, y, x + tlen, y], fill=(255, 255, 255), width=3)
    draw.line([x, y, x, y + tlen], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y, x + CELL - tlen, y], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y, x + CELL, y + tlen], fill=(255, 255, 255), width=3)
    draw.line([x, y + CELL, x + tlen, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x, y + CELL, x, y + CELL - tlen], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y + CELL, x + CELL - tlen, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y + CELL, x + CELL, y + CELL - tlen], fill=(255, 255, 255), width=3)
    
    # Text
    draw.text((x + 2, y + CELL + 8), title, fill=(240, 245, 250), font=font_card)
    draw.text((x + 2, y + CELL + 30), sub, fill=tag_col, font=font_desc)

showcase_out = OUT_DIR / "new_seamless_rice_field_showcase.png"
canvas.save(showcase_out)
canvas.save(ARTIFACT_DIR / "new_seamless_rice_field_showcase.png")
print("Saved final showcase:", showcase_out)

