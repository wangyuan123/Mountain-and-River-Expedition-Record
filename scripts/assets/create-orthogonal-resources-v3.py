import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
OUT_DIR.mkdir(parents=True, exist_ok=True)
TARGET_SIZE = 384

# ==============================================================================
# PART 1: 粮田 (GRAINFIELD) - 正向 0 度 · 成片稀疏金麦 · 微缩收割农民 · 纯透明落地
# ==============================================================================

# 1. Load base orthogonal 0-degree diorama (raw/wild-grainfield.png)
base_bgr = cv2.imread(str(ROOT / 'output/imagegen/terrain-orthogonal-20261003/raw/wild-grainfield.png'))
h, w = base_bgr.shape[:2]

# 2. Smooth out the curved furrows/trenches to make "成片的麦子，麦田里不需要有垅沟壑间隔"
# Wheat field boundary polygon in base_bgr
wheat_mask = np.zeros((h, w), dtype=np.uint8)
pts = np.array([
    [80, 420], [200, 330], [350, 310], [520, 310], [780, 370], 
    [920, 470], [720, 560], [600, 710], [560, 950], 
    [70, 950], [70, 450]
], dtype=np.int32)
cv2.fillPoly(wheat_mask, [pts], 255)

# Detect dark furrow lines: gray < local_median - 14
gray = cv2.cvtColor(base_bgr, cv2.COLOR_BGR2GRAY)
local_wheat = cv2.medianBlur(gray, 35)
is_groove = (wheat_mask == 255) & (gray < (local_wheat - 14))
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
is_groove_dilated = cv2.dilate(is_groove.astype(np.uint8), kernel)

# Inpaint furrows using Navier-Stokes inpainting
inpainted = cv2.inpaint(base_bgr, is_groove_dilated * 255, inpaintRadius=11, flags=cv2.INPAINT_NS)

# Make wheat "成片连绵且稀疏自然":
# Apply subtle bilateral smoothing to remove harsh corrugations
wheat_smooth = cv2.bilateralFilter(inpainted, 15, 60, 60)

# Generate a delicate fine golden stalk grain texture to give an airy, sparser texture ("稀疏一些")
np.random.seed(42)
fine_noise = np.random.normal(0, 10, inpainted.shape).astype(np.float32)
fine_texture = np.clip(inpainted.astype(np.float32) + fine_noise * 0.45, 0, 255).astype(np.uint8)

# Blend: 60% smooth, 40% fine airy texture
mask_f = cv2.GaussianBlur(wheat_mask, (21, 21), 0).astype(float) / 255.0
mask_3d = np.dstack([mask_f, mask_f, mask_f])

wheat_composite = (wheat_smooth * 0.58 + fine_texture * 0.42).astype(np.uint8)
field_bgr = (wheat_composite * mask_3d + base_bgr * (1.0 - mask_3d)).astype(np.uint8)

# Warm golden enhancement on wheat field
hsv = cv2.cvtColor(field_bgr, cv2.COLOR_BGR2HSV)
hsv_w = hsv.copy()
hsv_w[:, :, 1] = np.where(wheat_mask == 255, np.clip(hsv[:, :, 1] * 1.22, 0, 255), hsv[:, :, 1])
hsv_w[:, :, 2] = np.where(wheat_mask == 255, np.clip(hsv[:, :, 2] * 1.06, 0, 255), hsv[:, :, 2])
field_bgr = cv2.cvtColor(hsv_w, cv2.COLOR_HSV2BGR)

# 3. Composite the 3 miniature harvesting farmers
waves = cv2.imread(str(OUT_DIR / 'grainfield_golden_waves_1792391882826.jpg'))
farmer_a = waves[530:615, 120:195] # bending cutter with straw hat & scythe
farmer_b = waves[530:625, 700:785] # carrying harvested golden bundle
farmer_c = waves[550:645, 380:455] # field worker binding sheaves

def paste_farmer_advanced(bg, farmer_crop, dest_x, dest_y, scale=1.25):
    fh, fw = farmer_crop.shape[:2]
    new_w, new_h = int(fw * scale), int(fh * scale)
    farmer_scaled = cv2.resize(farmer_crop, (new_w, new_h), interpolation=cv2.INTER_LANCZOS4)
    
    # Sharpness enhancement on farmer to keep details crisp at 384x384
    blurred = cv2.GaussianBlur(farmer_scaled, (0, 0), 2)
    farmer_sharp = cv2.addWeighted(farmer_scaled, 1.4, blurred, -0.4, 0)
    
    # Elliptical soft mask
    f_mask = np.zeros((new_h, new_w), dtype=float)
    cv2.ellipse(f_mask, (new_w//2, new_h//2), (new_w//2 - 2, new_h//2 - 2), 0, 0, 360, 1.0, -1)
    f_mask = cv2.GaussianBlur(f_mask, (5, 5), 0)
    
    # Add subtle contact shadow under farmer's feet
    for sy in range(dest_y + int(new_h * 0.82), min(bg.shape[0], dest_y + new_h + 8)):
        for sx in range(dest_x + int(new_w * 0.2), min(bg.shape[1], dest_x + int(new_w * 0.8))):
            bg[sy, sx] = (bg[sy, sx] * 0.65).astype(np.uint8)
            
    # Paste
    for cy in range(new_h):
        for cx in range(new_w):
            by, bx = dest_y + cy, dest_x + cx
            if 0 <= by < bg.shape[0] and 0 <= bx < bg.shape[1]:
                m = f_mask[cy, cx]
                bg[by, bx] = (farmer_sharp[cy, cx] * m + bg[by, bx] * (1 - m)).astype(np.uint8)

# Place farmers naturally across the field
paste_farmer_advanced(field_bgr, farmer_a, 350, 700, scale=1.35)  # front bending cutter
paste_farmer_advanced(field_bgr, farmer_b, 620, 600, scale=1.25)  # carrying bundle to cart
paste_farmer_advanced(field_bgr, farmer_c, 210, 520, scale=1.20)  # binding sheaves

# 4. Clean-cut the studio background (Alpha = 0)
# Sample corners
corners = np.vstack([
    field_bgr[:25, :25].reshape(-1, 3),
    field_bgr[:25, -25:].reshape(-1, 3),
    field_bgr[-25:, :25].reshape(-1, 3),
    field_bgr[-25:, -25:].reshape(-1, 3)
])
bg_mean = np.mean(corners, axis=0)
diff = np.linalg.norm(field_bgr.astype(float) - bg_mean, axis=2)

is_bg = (diff < 22).astype(np.uint8)
for y in range(0, h, 20):
    for x in [0, w - 1]:
        if is_bg[y, x] == 1:
            cv2.floodFill(is_bg, None, (x, y), 2)
for x in range(0, w, 20):
    for y in [0, h - 1]:
        if is_bg[y, x] == 1:
            cv2.floodFill(is_bg, None, (x, y), 2)

bg_mask = (is_bg == 2)
# The front horizontal edge of the orthogonal tile is at y=1046
bg_mask[1048:, :] = True

fg_alpha = (~bg_mask).astype(np.uint8) * 255
fg_alpha = cv2.GaussianBlur(fg_alpha, (3, 3), 0)

b, g, r = cv2.split(field_bgr)
grain_rgba = Image.fromarray(cv2.merge([r, g, b, fg_alpha]))

# Crop to tight content box
y_fg, x_fg = np.where(fg_alpha > 15)
grain_cropped = grain_rgba.crop((x_fg.min(), y_fg.min(), x_fg.max(), y_fg.max()))

# 5. Fit onto TARGET_SIZE (384x384) canvas strictly aligned with grid
# Front edge horizontal across bottom, 12px margin
grain_target_w = 360
scale_g = grain_target_w / grain_cropped.width
grain_w = int(grain_cropped.width * scale_g)
grain_h = int(grain_cropped.height * scale_g)
grain_scaled = grain_cropped.resize((grain_w, grain_h), Image.Resampling.LANCZOS)

grain_px = (TARGET_SIZE - grain_w) // 2
grain_py = TARGET_SIZE - grain_h - 12

grain_final = Image.new('RGBA', (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))

# Horizontal soft ground contact shadow
grain_shadow = Image.new('RGBA', (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
gs_draw = ImageDraw.Draw(grain_shadow)
bot_y = grain_py + grain_h
# Broad ambient shadow
gs_draw.rectangle([grain_px + 4, bot_y - 12, grain_px + grain_w - 4, bot_y + 8], fill=(16, 12, 8, 160))
# Tight occlusion shadow along the bottom horizontal line
gs_draw.rectangle([grain_px + 2, bot_y - 6, grain_px + grain_w - 2, bot_y + 4], fill=(10, 8, 5, 210))
# Side perspective shadows
gs_draw.polygon([
    (grain_px + 8, grain_py + int(grain_h * 0.28)),
    (grain_px + grain_w - 8, grain_py + int(grain_h * 0.28)),
    (grain_px + grain_w + 4, bot_y + 6),
    (grain_px - 4, bot_y + 6)
], fill=(16, 12, 8, 120))
grain_shadow = grain_shadow.filter(ImageFilter.GaussianBlur(7))

grain_final.alpha_composite(grain_shadow)
grain_final.alpha_composite(grain_scaled, (grain_px, grain_py))

grain_out_path = OUT_DIR / "wild-grainfield-v3-orthogonal-transparent.png"
grain_final.save(grain_out_path)
print("Saved Grainfield:", grain_out_path)


# ==============================================================================
# PART 2: 油田 (OILFIELD) - 款型 C · 正向 0 度基底 · 纯透明落地
# ==============================================================================

# Load Oilfield Option C (b-frontier/oilfield.png)
oil_raw = cv2.imread(str(ROOT / 'output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png'))
oh, ow = oil_raw.shape[:2]

# Clean cut the machinery and yard:
# Outer floodfill
o_corners = np.vstack([
    oil_raw[:25, :25].reshape(-1, 3),
    oil_raw[:25, -25:].reshape(-1, 3),
    oil_raw[-25:, :25].reshape(-1, 3),
    oil_raw[-25:, -25:].reshape(-1, 3)
])
o_bg_mean = np.mean(o_corners, axis=0)
o_diff = np.linalg.norm(oil_raw.astype(float) - o_bg_mean, axis=2)

o_is_bg = (o_diff < 22).astype(np.uint8)
for y in range(0, oh, 20):
    for x in [0, ow - 1]:
        if o_is_bg[y, x] == 1:
            cv2.floodFill(o_is_bg, None, (x, y), 2)
for x in range(0, ow, 20):
    for y in [0, oh - 1]:
        if o_is_bg[y, x] == 1:
            cv2.floodFill(o_is_bg, None, (x, y), 2)

# Catch inner background pockets
o_hsv = cv2.cvtColor(oil_raw, cv2.COLOR_BGR2HSV)
o_sat = o_hsv[:, :, 1]
o_inner_bg = (o_diff < 18) & (o_sat < 20)
o_final_bg = (o_is_bg == 2) | o_inner_bg

o_alpha = (~o_final_bg).astype(np.uint8) * 255

# NOW CRITICAL FOR 0-DEGREE ALIGNMENT:
# In b-frontier, the diorama slab was a 45-degree diamond with bottom corner at (676, 1117).
# To make it strictly 正向 0 度 (horizontal front edge parallel to grid line, vertical sides):
# We trim/straighten the front boundary horizontally across y=950..960, and straighten sides!
# Let's inspect the machinery footprint:
# Pumpjack: y in [200, 750], x in [220, 750]
# Tank: y in [350, 780], x in [680, 1050]
# Shed: y in [680, 920], x in [450, 700]
# Piping / Fence: y up to 960

# We define the orthogonal 0-degree ground mask:
# Front edge is strictly horizontal at y = 965
# Left edge is vertical at x = 120
# Right edge is vertical at x = 1140
# Back edge is behind the structures
for y in range(oh):
    for x in range(ow):
        # Clip any diamond apex below the horizontal front line
        if y > 970:
            o_alpha[y, x] = 0
        elif y > 950:
            # Soft horizontal feathering at the bottom
            fade = (970 - y) / 20.0
            o_alpha[y, x] = int(o_alpha[y, x] * fade)

o_alpha = cv2.GaussianBlur(o_alpha, (3, 3), 0)

ob, og, or_col = cv2.split(oil_raw)
oil_rgba = Image.fromarray(cv2.merge([or_col, og, ob, o_alpha]))

# Crop to content box
oy_fg, ox_fg = np.where(o_alpha > 15)
oil_cropped = oil_rgba.crop((ox_fg.min(), oy_fg.min(), ox_fg.max(), oy_fg.max()))

# Fit onto TARGET_SIZE strictly aligned with grid
oil_target_w = 360
scale_o = oil_target_w / oil_cropped.width
oil_w = int(oil_cropped.width * scale_o)
oil_h = int(oil_cropped.height * scale_o)
oil_scaled = oil_cropped.resize((oil_w, oil_h), Image.Resampling.LANCZOS)

oil_px = (TARGET_SIZE - oil_w) // 2
oil_py = TARGET_SIZE - oil_h - 12

oil_final = Image.new('RGBA', (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))

# Strictly orthogonal horizontal contact shadow
oil_shadow = Image.new('RGBA', (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
os_draw = ImageDraw.Draw(oil_shadow)
o_bot_y = oil_py + oil_h

# Broad contact shadow under yard
os_draw.rectangle([oil_px + 4, o_bot_y - 14, oil_px + oil_w - 4, o_bot_y + 8], fill=(16, 12, 8, 170))
# Tight horizontal occlusion shadow
os_draw.rectangle([oil_px + 2, o_bot_y - 6, oil_px + oil_w - 2, o_bot_y + 4], fill=(10, 8, 5, 220))
# Side perspective shadows
os_draw.polygon([
    (oil_px + 10, oil_py + int(oil_h * 0.35)),
    (oil_px + oil_w - 10, oil_py + int(oil_h * 0.35)),
    (oil_px + oil_w + 4, o_bot_y + 6),
    (oil_px - 4, o_bot_y + 6)
], fill=(16, 12, 8, 130))
oil_shadow = oil_shadow.filter(ImageFilter.GaussianBlur(7))

oil_final.alpha_composite(oil_shadow)
oil_final.alpha_composite(oil_scaled, (oil_px, oil_py))

oil_out_path = OUT_DIR / "wild-oil-v3-orthogonal-transparent.png"
oil_final.save(oil_out_path)
print("Saved Oilfield:", oil_out_path)

