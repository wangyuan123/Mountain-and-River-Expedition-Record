import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")
TARGET = 384

# Load 款型 B (wheat with farmers)
raw_b = cv2.imread(str(OUT_DIR / "refined_grain_opt2_farmers_waves.png"))
h, w = raw_b.shape[:2]

# Baseline terrain colors
GRASS = (104, 103, 71)    # 橄榄金绿
SOIL = (92, 81, 62)       # 深棕褐土

def smooth(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3.0 - 2.0 * t)

# ==============================================================================
# BUILD ORGANIC ZERO-CLIPPING ALPHA & TERRAIN FEATHERING
# ==============================================================================
cx, cy = 192.0, 192.0
# Half size: 172px (gives 20px safety margin on all sides, completely inside 384 canvas)
half_size = 170.0
corner_r = 24.0

alpha_map = np.zeros((TARGET, TARGET), dtype=np.float32)
edge_factor_map = np.zeros((TARGET, TARGET), dtype=np.float32)

for y in range(TARGET):
    for x in range(TARGET):
        dx = abs(x - cx)
        dy = abs(y - cy)
        
        # Rounded square skeletal footprint (aligned with cardinal directions)
        corner_dx = max(0.0, dx - (half_size - corner_r))
        corner_dy = max(0.0, dy - (half_size - corner_r))
        corner_dist = math.sqrt(corner_dx * corner_dx + corner_dy * corner_dy)
        d_base = (half_size - max(dx, dy)) if (corner_dx == 0 or corner_dy == 0) else (corner_r - corner_dist)
        
        # Multi-frequency organic noise: simulating natural wheat stalk tufts, soil tongue extensions
        n1 = math.sin(x * 0.045 + 1.2) * 8.5 + math.cos(y * 0.052 - 0.7) * 7.5
        n2 = math.sin(x * 0.095 - y * 0.065) * 5.0 + math.sin(y * 0.11 + x * 0.04) * 4.0
        n3 = math.sin(x * 0.24 + y * 0.18) * 2.2
        edge_noise = n1 + n2 + n3
        
        d_eff = d_base + edge_noise
        
        # Feathering alpha from d_eff = -4.0 (0% opacity) to d_eff = 24.0 (100% opacity)
        af = smooth(-4.0, 22.0, d_eff)
        alpha_map[y, x] = af
        
        # Outer feathering zone factor (for subtle terrain blending at the outermost 12px fringe)
        # ef is 1.0 at outer edge, 0.0 inside wheat core
        ef = 1.0 - smooth(0.0, 18.0, d_eff)
        edge_factor_map[y, x] = max(0.0, min(1.0, ef))

# Soften alpha slightly
alpha_uint8 = (alpha_map * 255.0).astype(np.uint8)
alpha_uint8 = cv2.GaussianBlur(alpha_uint8, (3, 3), 0)

# Build RGBA model
# In the outer feathering fringe, gently warm the RGB towards natural humus/grass border
b, g, r = cv2.split(raw_b)
b_f = b.astype(float)
g_f = g.astype(float)
r_f = r.astype(float)

# Organic RGBA
grain_no_clip_bgr = np.zeros((TARGET, TARGET, 4), dtype=np.uint8)
grain_no_clip_bgr[:, :, 0] = b
grain_no_clip_bgr[:, :, 1] = g
grain_no_clip_bgr[:, :, 2] = r
grain_no_clip_bgr[:, :, 3] = alpha_uint8

pil_grain_fg = Image.fromarray(cv2.cvtColor(grain_no_clip_bgr, cv2.COLOR_BGRA2RGBA))

# ==============================================================================
# REALISTIC AMBIENT GROUND CONTACT SHADOW
# ==============================================================================
shadow = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
s_draw = ImageDraw.Draw(shadow)

# Soft rounded polygon matching the square footprint with 14px blur
s_draw.rounded_rectangle([14, 14, TARGET - 14, TARGET - 14], radius=24, fill=(16, 12, 8, 140))
# Tighter contact occlusion shadow along the bottom front edge
s_draw.rounded_rectangle([18, TARGET - 32, TARGET - 18, TARGET - 12], radius=10, fill=(10, 8, 5, 200))
shadow = shadow.filter(ImageFilter.GaussianBlur(11))

# Final composite model
final_grain_model = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
final_grain_model.alpha_composite(shadow)
final_grain_model.alpha_composite(pil_grain_fg)

# Save master asset
final_out = OUT_DIR / "wild-grainfield-no-clipping-organic.png"
final_grain_model.save(final_out)
print("Saved organic zero-clipping grainfield:", final_out)


# ==============================================================================
# BUILD BEFORE-VS-AFTER IN-MAP COMPARISON SHEET
# ==============================================================================
# Compare:
# Column 1: 旧版硬切满格（在草坪上）-> 截断感强烈
# Column 2: 优化后零截断有机过渡（在草坪上）-> 平滑自然无截断
# Column 3: 旧版硬切满格（在黑土上）-> 截断感强烈
# Column 4: 优化后零截断有机过渡（在黑土上）-> 平滑自然无截断

CELL = 260
PAD = 24
TOP_H = 110
CARD_H = CELL + 50
TOTAL_W = PAD * 5 + CELL * 4
TOTAL_H = TOP_H + CARD_H + PAD + 10

canvas = Image.new("RGB", (TOTAL_W, TOTAL_H), (18, 22, 28))
draw = ImageDraw.Draw(canvas)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 26)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
font_card = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_desc = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 13)

# Header
draw.text((PAD, 18), "大地图粮田：【消除边缘生硬截断】对比效果展示", fill=(245, 248, 252), font=font_title)
draw.text((PAD, 58), "安全边距内缩 · 有机麦穗与泥土自然过渡 · 真实地表接触软阴影 · 彻底告别壁纸贴纸式刀切截断感", fill=(255, 215, 0), font=font_sub)

# Hard-clipped version (original raw_b)
hard_clip_img = Image.open(OUT_DIR / "refined_grain_opt2_farmers_waves.png").resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)
# Organic zero-clip version
organic_img = final_grain_model.resize((CELL - 4, CELL - 4), Image.Resampling.LANCZOS)

panels = [
    ("【硬切截断】草坪环境", GRASS, hard_clip_img, "四周直线生硬切断", (255, 80, 80), False),
    ("【零截断·有机过渡】草坪环境", GRASS, organic_img, "草丛自然过渡/软阴影接地", (100, 230, 140), True),
    ("【硬切截断】黑土环境", SOIL, hard_clip_img, "四周直线生硬切断", (255, 80, 80), False),
    ("【零截断·有机过渡】黑土环境", SOIL, organic_img, "泥草自然交融/软阴影接地", (100, 230, 140), True)
]

for i, (title, bg_col, model, desc, tag_col, is_good) in enumerate(panels):
    x = PAD + i * (CELL + PAD)
    y = TOP_H + 16
    
    # Terrain cell
    cell = Image.new("RGBA", (CELL, CELL), (*bg_col, 255))
    c_arr = np.array(cell)
    np.random.seed(300 + i)
    c_arr[:, :, :3] = np.clip(c_arr[:, :, :3] + np.random.randint(-7, 7, c_arr[:, :, :3].shape), 0, 255)
    cell = Image.fromarray(c_arr.astype(np.uint8))
    
    if is_good:
        cell.alpha_composite(model, (2, 2))
    else:
        # Paste hard cut
        cell.paste(model, (2, 2))
        
    canvas.paste(cell.convert("RGB"), (x, y))
    
    # Border
    b_col = (255, 80, 80) if not is_good else (80, 220, 120)
    draw.rectangle([x, y, x + CELL, y + CELL], outline=b_col, width=2)
    
    draw.text((x + 4, y + CELL + 8), title, fill=(240, 245, 250), font=font_card)
    draw.text((x + 4, y + CELL + 30), desc, fill=tag_col, font=font_desc)

comp_path = OUT_DIR / "grainfield_clipping_fix_comparison.png"
canvas.save(comp_path)
canvas.save(ARTIFACT_DIR / "grainfield_clipping_fix_comparison.png")
print("Saved comparison showcase:", comp_path)

