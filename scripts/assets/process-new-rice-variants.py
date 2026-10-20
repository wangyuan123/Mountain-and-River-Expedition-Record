import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageFont
from pathlib import Path

OUT_DIR = Path("output/imagegen/redesign-oil-grain-20261019")
OUT_DIR.mkdir(parents=True, exist_ok=True)
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")
TARGET = 384

# Paths to the newly generated images
v1_path = ARTIFACT_DIR / "ricefield_paddy_v1_1792423238204.jpg"
v2_path = ARTIFACT_DIR / "ricefield_paddy_v2_1792423271653.jpg"

def process_rice_variant(img_path, out_name):
    img = cv2.imread(str(img_path))
    h, w = img.shape[:2]
    
    # Clean background isolation from pure white studio background
    # Background is #ffffff or near white (R,G,B > 240)
    # The diorama itself has golden yellow rice, earthen bund, water, farmers
    
    # Distance from white [255, 255, 255]
    diff_from_white = np.linalg.norm(img.astype(float) - 255.0, axis=2)
    
    # Binary background candidate: diff < 20
    is_white_bg = (diff_from_white < 20).astype(np.uint8)
    
    # Floodfill from 4 outer borders to only catch exterior background
    # and preserve any white clothes or highlights inside the diorama
    exterior_bg = np.zeros((h, w), dtype=np.uint8)
    flood = is_white_bg.copy()
    
    for y in range(0, h, 20):
        for x in [0, w - 1]:
            if flood[y, x] == 1:
                cv2.floodFill(flood, None, (x, y), 2)
    for x in range(0, w, 20):
        for y in [0, h - 1]:
            if flood[y, x] == 1:
                cv2.floodFill(flood, None, (x, y), 2)
                
    exterior_mask = (flood == 2)
    
    # Also detect the soft contact shadow on white background:
    # Shadow pixels have low saturation and moderate lightness (e.g. diff_from_white in [20, 90])
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    val = hsv[:, :, 2]
    
    # Build smooth alpha
    alpha = np.ones((h, w), dtype=np.float32)
    
    # For exterior background:
    # If it's pure white exterior: alpha = 0
    # If it's soft shadow (exterior, low saturation, val > 160): retain as smooth shadow
    is_shadow = exterior_mask & (sat < 35) & (val > 150) & (val < 250)
    
    for y in range(h):
        for x in range(w):
            if exterior_mask[y, x]:
                if is_shadow[y, x]:
                    # soft shadow alpha
                    shadow_strength = (255.0 - val[y, x]) / 105.0
                    alpha[y, x] = max(0.0, min(0.65, shadow_strength))
                else:
                    alpha[y, x] = 0.0
                    
    # Feather alpha slightly at edges
    alpha_uint8 = (alpha * 255.0).astype(np.uint8)
    alpha_uint8 = cv2.GaussianBlur(alpha_uint8, (3, 3), 0)
    
    b, g, r = cv2.split(img)
    rgba = cv2.merge([r, g, b, alpha_uint8])
    pil_rgba = Image.fromarray(rgba)
    
    # Check bounding box of non-zero alpha
    y_idx, x_idx = np.where(alpha_uint8 > 15)
    bbox = (x_idx.min(), y_idx.min(), x_idx.max(), y_idx.max())
    print(f"{out_name} content bbox:", bbox, f"w={bbox[2]-bbox[0]}, h={bbox[3]-bbox[1]}")
    
    # Resize to fit inside TARGET (384x384) with GENEROUS BREATHING MARGIN (24px padding on sides)
    # Target content size: ~336x336 inside 384x384, leaving 24px space on all sides!
    content = pil_rgba.crop(bbox)
    content_w, content_h = content.size
    
    # Scale content to fit comfortably with breathing margin
    max_dim = 336
    scale = max_dim / max(content_w, content_h)
    new_w = int(content_w * scale)
    new_h = int(content_h * scale)
    scaled_content = content.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    # Place on 384x384 canvas
    canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
    paste_x = (TARGET - new_w) // 2
    paste_y = (TARGET - new_h) // 2
    
    canvas.alpha_composite(scaled_content, (paste_x, paste_y))
    
    out_file = OUT_DIR / f"{out_name}.png"
    canvas.save(out_file)
    print(f"Saved {out_file}")
    return canvas

print("Processing Variant 1...")
v1_model = process_rice_variant(v1_path, "rice_variant_1_waterway")

print("Processing Variant 2...")
v2_model = process_rice_variant(v2_path, "rice_variant_2_cart")

# Build side-by-side in-map preview on Grass and Black soil
GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

CELL = 260
PAD = 24
TOP_H = 100
LABEL_H = 50

# 4 columns:
# Col 1: Variant 1 on Grass
# Col 2: Variant 1 on Soil
# Col 3: Variant 2 on Grass
# Col 4: Variant 2 on Soil

preview_w = PAD * 5 + CELL * 4
preview_h = TOP_H + CELL + LABEL_H + PAD

sheet = Image.new("RGB", (preview_w, preview_h), (18, 22, 28))
s_draw = ImageDraw.Draw(sheet)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 24)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)
font_label = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_desc = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 12)

s_draw.text((PAD, 18), "全新设计粮田：【连成一片水稻 · 底座透明 · 留有间隔空间零截断】实机嵌入效果", fill=(245, 248, 252), font=font_title)
s_draw.text((PAD, 56), "纯透明落地 · 边缘留足呼吸间隔空间 · 彻底解决水稻在边缘被切半截问题 · 水平对齐地图网格", fill=(255, 215, 0), font=font_sub)

tests = [
    ("款式一：【规整石渠·金稻方阵】", "嵌入大地图草坪 (橄榄金绿)", GRASS, v1_model, (255, 215, 0)),
    ("款式一：【规整石渠·金稻方阵】", "嵌入大地图黑土 (深棕褐色)", SOIL, v1_model, (255, 215, 0)),
    ("款式二：【丰收木车·金稻平原】", "嵌入大地图草坪 (橄榄金绿)", GRASS, v2_model, (100, 220, 255)),
    ("款式二：【丰收木车·金稻平原】", "嵌入大地图黑土 (深棕褐色)", SOIL, v2_model, (100, 220, 255)),
]

for i, (title, sub, bg_col, model, tag_col) in enumerate(tests):
    x = PAD + i * (CELL + PAD)
    y = TOP_H + 10
    
    # Background terrain cell
    cell = Image.new("RGBA", (CELL, CELL), (*bg_col, 255))
    c_arr = np.array(cell)
    np.random.seed(500 + i)
    c_arr[:, :, :3] = np.clip(c_arr[:, :, :3] + np.random.randint(-7, 7, c_arr[:, :, :3].shape), 0, 255)
    cell = Image.fromarray(c_arr.astype(np.uint8))
    
    # Scale model to cell
    scaled_m = model.resize((CELL, CELL), Image.Resampling.LANCZOS)
    cell.alpha_composite(scaled_m)
    
    sheet.paste(cell.convert("RGB"), (x, y))
    
    # Grid border
    s_draw.rectangle([x, y, x + CELL, y + CELL], outline=tag_col, width=2)
    
    # Labels
    s_draw.text((x + 2, y + CELL + 8), title, fill=(240, 245, 250), font=font_label)
    s_draw.text((x + 2, y + CELL + 30), sub, fill=tag_col, font=font_desc)

preview_file = OUT_DIR / "new_rice_variants_preview.png"
sheet.save(preview_file)
sheet.save(ARTIFACT_DIR / "new_rice_variants_preview.png")
print("Saved preview sheet:", preview_file)

