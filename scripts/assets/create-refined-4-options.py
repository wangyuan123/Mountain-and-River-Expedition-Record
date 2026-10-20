import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
TARGET = 384

# ==============================================================================
# 款型 1：【规整水网·金稻方阵】 (Option 1)
# ==============================================================================
raw_1 = Image.open(OUT_DIR / "grainfield_option_one_1792391934850.jpg")
opt_1 = raw_1.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
# Enhance colors: rich golden amber & warm soil tones
opt_1 = ImageEnhance.Color(opt_1).enhance(1.18)
opt_1 = ImageEnhance.Contrast(opt_1).enhance(1.08)
opt_1.save(OUT_DIR / "refined_grain_opt1_paddy_grid.png")

# ==============================================================================
# 款型 2：【连绵麦浪·割麦农民】 (Option 2)
# ==============================================================================
# Based on grainfield_golden_waves, cropped to pure golden wheat field
raw_waves = cv2.imread(str(OUT_DIR / "grainfield_golden_waves_1792391882826.jpg"))
# Center crop y: [260, 860], x: [240, 840]
crop_waves = raw_waves[260:860, 240:840].copy()
# Inpaint any corner white pixels (where BGR > 230)
is_white = np.all(crop_waves > 225, axis=2)
if np.any(is_white):
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    is_white_dil = cv2.dilate(is_white.astype(np.uint8), kernel)
    crop_waves = cv2.inpaint(crop_waves, is_white_dil * 255, inpaintRadius=7, flags=cv2.INPAINT_TELEA)

# Resize to TARGET x TARGET
opt_2_cv = cv2.resize(crop_waves, (TARGET, TARGET), interpolation=cv2.INTER_LANCZOS4)

# Composite extra sharp miniature farmers into opt_2:
# Farmer 1: bending cutter at (x=110, y=260)
# Farmer 2: carrying wheat bundle at (x=240, y=190)
farmer_a = cv2.imread(str(OUT_DIR / "farmer1_extracted.png"))
farmer_b = cv2.imread(str(OUT_DIR / "farmer2_extracted.png"))

def overlay_farmer(bg, f_img, px, py, scale=0.55):
    fh, fw = f_img.shape[:2]
    nw, nh = int(fw * scale), int(fh * scale)
    f_sc = cv2.resize(f_img, (nw, nh), interpolation=cv2.INTER_LANCZOS4)
    # Mask
    f_gray = cv2.cvtColor(f_sc, cv2.COLOR_BGR2GRAY)
    # Contact shadow
    for sy in range(py + int(nh*0.8), min(bg.shape[0], py + nh + 4)):
        for sx in range(px + int(nw*0.2), min(bg.shape[1], px + int(nw*0.8))):
            bg[sy, sx] = (bg[sy, sx] * 0.6).astype(np.uint8)
    # Paste
    for y in range(nh):
        for x in range(nw):
            by, bx = py + y, px + x
            if 0 <= by < bg.shape[0] and 0 <= bx < bg.shape[1]:
                # blend
                bg[by, bx] = f_sc[y, x]

if farmer_a is not None:
    overlay_farmer(opt_2_cv, farmer_a, 110, 250, scale=0.55)
if farmer_b is not None:
    overlay_farmer(opt_2_cv, farmer_b, 240, 180, scale=0.50)

# Convert BGR to RGB for PIL
opt_2 = Image.fromarray(cv2.cvtColor(opt_2_cv, cv2.COLOR_BGR2RGB))
opt_2 = ImageEnhance.Color(opt_2).enhance(1.15)
opt_2.save(OUT_DIR / "refined_grain_opt2_farmers_waves.png")

# ==============================================================================
# 款型 3：【战备机械化·红拖拉机麦田】 (Option 3)
# ==============================================================================
# Based on grainfield_option_two_1792391963423.jpg, cropped to remove all white border
raw_3 = cv2.imread(str(OUT_DIR / "grainfield_option_two_1792391963423.jpg"))
crop_3 = raw_3[48:1023, 18:993].copy()
opt_3_cv = cv2.resize(crop_3, (TARGET, TARGET), interpolation=cv2.INTER_LANCZOS4)
opt_3 = Image.fromarray(cv2.cvtColor(opt_3_cv, cv2.COLOR_BGR2RGB))
opt_3 = ImageEnhance.Color(opt_3).enhance(1.16)
opt_3 = ImageEnhance.Contrast(opt_3).enhance(1.06)
opt_3.save(OUT_DIR / "refined_grain_opt3_tractor_harvester.png")

# ==============================================================================
# 款型 4：【田园庄园·红顶谷仓与金麦】 (Option 4)
# ==============================================================================
# Rich golden wheat base with rustic barn and haystacks
opt_4_cv = opt_2_cv.copy()
# Place red-roof barn & grain crates in upper corner
barn_src = cv2.imread(str(ROOT / 'output/imagegen/realistic-buildings-20260917/c-industrial/farm.png'))
if barn_src is not None:
    barn_crop = barn_src[480:680, 520:760]
    barn_sc = cv2.resize(barn_crop, (140, 115))
    b_diff = np.abs(barn_sc.astype(float) - [200, 204, 207]).max(axis=2)
    b_mask = (b_diff > 25).astype(float)
    b_mask = cv2.GaussianBlur(b_mask, (5, 5), 0)
    b_mask_3d = np.dstack([b_mask, b_mask, b_mask])
    
    px, py = 225, 15
    bh, bw = barn_sc.shape[:2]
    # shadow
    for sy in range(py + int(bh*0.65), min(TARGET, py + bh + 10)):
        for sx in range(px - 4, min(TARGET, px + bw + 4)):
            opt_4_cv[sy, sx] = (opt_4_cv[sy, sx] * 0.52).astype(np.uint8)
    roi = opt_4_cv[py:py+bh, px:px+bw]
    opt_4_cv[py:py+bh, px:px+bw] = (barn_sc * b_mask_3d + roi * (1 - b_mask_3d)).astype(np.uint8)

opt_4 = Image.fromarray(cv2.cvtColor(opt_4_cv, cv2.COLOR_BGR2RGB))
opt_4 = ImageEnhance.Color(opt_4).enhance(1.15)
opt_4.save(OUT_DIR / "refined_grain_opt4_granary_homestead.png")

print("All 4 refined grainfield options created successfully.")
