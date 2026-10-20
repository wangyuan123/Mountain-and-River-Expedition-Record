import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")
TARGET = 384

# Source image: ricefield_paddy_v2
src_path = ARTIFACT_DIR / "ricefield_paddy_v2_1792423271653.jpg"
img_bgr = cv2.imread(str(src_path))
h, w = img_bgr.shape[:2]

# 1. Precise Matting: isolate subject and cast shadows from pure white background
# Sample background at 4 corners
bg_sample = np.mean([img_bgr[5, 5], img_bgr[5, -5], img_bgr[-5, 5], img_bgr[-5, -5]], axis=0)
diff = np.linalg.norm(img_bgr.astype(float) - [255.0, 255.0, 255.0], axis=2)

# Exterior background floodfill
is_white = (diff < 16).astype(np.uint8)
flood = is_white.copy()
for y in range(0, h, 15):
    for x in [0, w - 1]:
        if flood[y, x] == 1:
            cv2.floodFill(flood, None, (x, y), 2)
for x in range(0, w, 15):
    for y in [0, h - 1]:
        if flood[y, x] == 1:
            cv2.floodFill(flood, None, (x, y), 2)

exterior_mask = (flood == 2)

# HSV for shadow detection
hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
sat = hsv[:, :, 1]
val = hsv[:, :, 2]

# Alpha channel calculation
alpha = np.ones((h, w), dtype=np.float32)

# Exterior pixels:
# If very low saturation and high value (shadow on white), extract realistic shadow alpha
for y in range(h):
    for x in range(w):
        if exterior_mask[y, x]:
            if sat[y, x] < 30 and val[y, x] > 140:
                # Cast shadow: invert brightness
                shadow_alpha = (255.0 - val[y, x]) / 105.0
                alpha[y, x] = max(0.0, min(0.65, shadow_alpha))
            else:
                alpha[y, x] = 0.0

# Tight diorama bounds where alpha > 0.05
y_fg, x_fg = np.where(alpha > 0.05)
min_x, max_x = x_fg.min(), x_fg.max()
min_y, max_y = y_fg.min(), y_fg.max()

print(f"Content bbox in raw image: x=[{min_x}, {max_x}] (w={max_x - min_x}), y=[{min_y}, {max_y}] (h={max_y - min_y})")

# Build clean RGBA of content
b, g, r = cv2.split(img_bgr)
# In shadow regions, replace white background color with neutral dark shadow color (16, 12, 8)
r_clean = r.copy()
g_clean = g.copy()
b_clean = b.copy()
is_pure_shadow = exterior_mask & (alpha > 0.0) & (alpha < 0.9)
r_clean[is_pure_shadow] = 16
g_clean[is_pure_shadow] = 12
b_clean[is_pure_shadow] = 8

alpha_uint8 = (alpha * 255.0).astype(np.uint8)
alpha_uint8 = cv2.GaussianBlur(alpha_uint8, (3, 3), 0)

rgba_clean = cv2.merge([r_clean, g_clean, b_clean, alpha_uint8])
pil_crop = Image.fromarray(rgba_clean).crop((min_x, min_y, max_x, max_y))

# 2. Fit into TARGET (384x384) with GENEROUS BREATHING MARGIN (留足间隔空间)
# We want width ~328px (leaving 28px breathing margin on left & right)
# So absolutely NO RICE STALK touches or leaks half a stalk at the image borders!
crop_w, crop_h = pil_crop.size
target_content_w = 330
scale = target_content_w / crop_w
new_w = int(crop_w * scale)
new_h = int(crop_h * scale)
scaled_rice = pil_crop.resize((new_w, new_h), Image.Resampling.LANCZOS)

# Enhance golden richness of rice stalks
enhancer = ImageEnhance.Color(scaled_rice)
scaled_rice = enhancer.enhance(1.12)

# Place on 384x384 canvas
model_canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
paste_x = (TARGET - new_w) // 2
paste_y = (TARGET - new_h) // 2 + 10 # slightly grounded

model_canvas.alpha_composite(scaled_rice, (paste_x, paste_y))

# Save the master transparent asset
master_out = OUT_DIR / "wild-grainfield-rice-paddy-seamless.png"
model_canvas.save(master_out)
print(f"Saved master model: {master_out}")

# Check margins on 384x384 canvas:
arr_384 = np.array(model_canvas)
y_384, x_384 = np.where(arr_384[:, :, 3] > 20)
print(f"Final 384x384 model bounds: x in [{x_384.min()}, {x_384.max()}], y in [{y_384.min()}, {y_384.max()}]")
print(f"Safety spacing margins: Left={x_384.min()}px, Right={TARGET - 1 - x_384.max()}px, Top={y_384.min()}px, Bottom={TARGET - 1 - y_384.max()}px")

# 3. Simulate In-Map Embedding on Grassland and Black Soil
GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

sim_w = TARGET * 2 + 30
sim_h = TARGET + 80
sim_canvas = Image.new("RGB", (sim_w, sim_h), (20, 24, 30))
sim_draw = ImageDraw.Draw(sim_canvas)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 22)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)
font_card = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_desc = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 12)

sim_draw.text((15, 14), "大地图粮田微缩模型：【连成一片水稻 · 纯透明底座 · 边缘安全间距留白 · 零截断】实机效果", fill=(245, 248, 252), font=font_title)
sim_draw.text((15, 46), "水稻完全收敛于方格内 · 四周留有间隔空间 · 绝无边缘半截水稻截断 · 软阴影无缝落地自然融入野地", fill=(255, 215, 0), font=font_sub)

# Left: On Grassland
grass_tile = Image.new("RGBA", (TARGET, TARGET), (*GRASS, 255))
# add ground grain noise
g_arr = np.array(grass_tile)
np.random.seed(42)
g_arr[:, :, :3] = np.clip(g_arr[:, :, :3] + np.random.randint(-6, 6, g_arr[:, :, :3].shape), 0, 255)
grass_tile = Image.fromarray(g_arr.astype(np.uint8))
grass_tile.alpha_composite(model_canvas)

# Right: On Black Soil
soil_tile = Image.new("RGBA", (TARGET, TARGET), (*SOIL, 255))
s_arr = np.array(soil_tile)
np.random.seed(43)
s_arr[:, :, :3] = np.clip(s_arr[:, :, :3] + np.random.randint(-6, 6, s_arr[:, :, :3].shape), 0, 255)
soil_tile = Image.fromarray(s_arr.astype(np.uint8))
soil_tile.alpha_composite(model_canvas)

sim_canvas.paste(grass_tile.convert("RGB"), (10, 72))
sim_canvas.paste(soil_tile.convert("RGB"), (TARGET + 20, 72))

# Grid overlays (yellow/cyan border around 384 cell)
sim_draw.rectangle([10, 72, 10 + TARGET, 72 + TARGET], outline=(255, 215, 0), width=2)
sim_draw.rectangle([TARGET + 20, 72, TARGET + 20 + TARGET, 72 + TARGET], outline=(70, 160, 255), width=2)

# Corner ticks
for px in [10, TARGET + 20]:
    tlen = 16
    sim_draw.line([px, 72, px + tlen, 72], fill=(255, 255, 255), width=3)
    sim_draw.line([px, 72, px, 72 + tlen], fill=(255, 255, 255), width=3)
    sim_draw.line([px + TARGET, 72, px + TARGET - tlen, 72], fill=(255, 255, 255), width=3)
    sim_draw.line([px + TARGET, 72, px + TARGET, 72 + tlen], fill=(255, 255, 255), width=3)
    sim_draw.line([px, 72 + TARGET, px + tlen, 72 + TARGET], fill=(255, 255, 255), width=3)
    sim_draw.line([px, 72 + TARGET, px, 72 + TARGET - tlen], fill=(255, 255, 255), width=3)
    sim_draw.line([px + TARGET, 72 + TARGET, px + TARGET - tlen, 72 + TARGET], fill=(255, 255, 255), width=3)
    sim_draw.line([px + TARGET, 72 + TARGET, px + TARGET, 72 + TARGET - tlen], fill=(255, 255, 255), width=3)

sim_out = OUT_DIR / "seamless_rice_field_showcase.png"
sim_canvas.save(sim_out)
sim_canvas.save(ARTIFACT_DIR / "seamless_rice_field_showcase.png")
print(f"Saved showcase comparison: {sim_out}")

