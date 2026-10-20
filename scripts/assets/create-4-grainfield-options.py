import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
OUT_DIR.mkdir(parents=True, exist_ok=True)
TARGET = 384

# ==============================================================================
# 款型 A：【规整水网·金稻水田】 (Square Terraced Golden Rice Grid)
# 来源：grainfield_option_one_1792391934850.jpg
# 特点：100% 全格正交，规整水网田埂，金黄垂穗稻谷，四边完全与地图方格水平垂直重合
# ==============================================================================
raw_a = Image.open(OUT_DIR / "grainfield_option_one_1792391934850.jpg")
# Enhance contrast and golden richness slightly
enhancer = ImageEnhance.Color(raw_a)
enhanced_a = enhancer.enhance(1.15)
opt_a = enhanced_a.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
opt_a.save(OUT_DIR / "grainfield_option_1_paddy_grid.png")
print("Saved Option A:", OUT_DIR / "grainfield_option_1_paddy_grid.png")


# ==============================================================================
# 款型 B：【丰收麦浪·割麦农民】 (Golden Wheat Waves with Miniature Farmers)
# 来源：grainfield_golden_waves_1792391882826.jpg (crop white borders: y=[10, 1022], x=[0, 1023])
# 特点：成片连绵金黄麦浪，彻底消除垅沟壑，微观稀疏通透自然，田间多名微缩农民正在挥镰割麦与运粮
# ==============================================================================
raw_b = Image.open(OUT_DIR / "grainfield_golden_waves_1792391882826.jpg")
# Crop exact content bounds
crop_b = raw_b.crop((0, 10, 1024, 1022))
enhancer_b = ImageEnhance.Color(crop_b)
enhanced_b = enhancer_b.enhance(1.12)
opt_b = enhanced_b.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
opt_b.save(OUT_DIR / "grainfield_option_2_farmers_waves.png")
print("Saved Option B:", OUT_DIR / "grainfield_option_2_farmers_waves.png")


# ==============================================================================
# 款型 C：【战时机械化·拖拉机麦田】 (Mechanized Combine Harvester Prairie)
# 来源：grainfield_option_two_1792391963423.jpg (crop white borders: y=[7, 1023], x=[8, 1015])
# 特点：古典红色微缩拖拉机收割机在金黄麦海中平直开辟麦道，整齐麦茬与草捆，角落有后勤货车与粮箱
# ==============================================================================
raw_c = Image.open(OUT_DIR / "grainfield_option_two_1792391963423.jpg")
crop_c = raw_c.crop((8, 7, 1015, 1023))
enhancer_c = ImageEnhance.Color(crop_c)
enhanced_c = enhancer_c.enhance(1.10)
opt_c = enhanced_c.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
opt_c.save(OUT_DIR / "grainfield_option_3_tractor_harvester.png")
print("Saved Option C:", OUT_DIR / "grainfield_option_3_tractor_harvester.png")


# ==============================================================================
# 款型 D：【田园庄园·谷仓麦垛庄园】 (Homestead Granary & Haystack Field)
# 来源：基于欧陆乡村庄园，搭配金黄麦田、微缩圆锥麦草垛（Haystacks）、红顶谷仓与运粮木车
# ==============================================================================
# Let's create an elegant composition using high-res wheat field + rustic wooden granary & haystacks
# Base: golden wheat field from enhanced_b
opt_d_cv = np.array(opt_b).copy()
# In the upper-right corner (x in [220, 360], y in [20, 160]), place a rustic red-roof granary and haystacks
# Let's extract a lovely barn/granary and haystacks or synthesize:
# In opt_c, upper-right has rustic grain crates and farm structures
# Let's composite a classic homestead granary into opt_d
granary_tile = cv2.imread(str(ROOT / 'output/imagegen/realistic-buildings-20260917/c-industrial/farm.png'))
if granary_tile is not None:
    # Extract the farmhouse / granary at (y: 450..700, x: 500..800)
    barn_crop = granary_tile[480:680, 520:760]
    barn_scaled = cv2.resize(barn_crop, (150, 125))
    
    # Mask barn (remove gray studio background)
    b_gray = cv2.cvtColor(barn_scaled, cv2.COLOR_BGR2GRAY)
    b_diff = np.abs(barn_scaled.astype(float) - [200, 204, 207]).max(axis=2)
    b_mask = (b_diff > 25).astype(float)
    b_mask = cv2.GaussianBlur(b_mask, (5, 5), 0)
    b_mask_3d = np.dstack([b_mask, b_mask, b_mask])
    
    # Paste onto upper right of opt_d
    px, py = 215, 20
    bh, bw = barn_scaled.shape[:2]
    # Add ground shadow under barn
    for sy in range(py + int(bh*0.7), min(TARGET, py + bh + 12)):
        for sx in range(px - 6, min(TARGET, px + bw + 6)):
            opt_d_cv[sy, sx] = (opt_d_cv[sy, sx] * 0.55).astype(np.uint8)
            
    roi = opt_d_cv[py:py+bh, px:px+bw]
    # Convert BGR barn to RGB for PIL array
    barn_rgb = cv2.cvtColor(barn_scaled, cv2.COLOR_BGR2RGB)
    opt_d_cv[py:py+bh, px:px+bw] = (barn_rgb * b_mask_3d + roi * (1 - b_mask_3d)).astype(np.uint8)

opt_d = Image.fromarray(opt_d_cv)
opt_d.save(OUT_DIR / "grainfield_option_4_granary_homestead.png")
print("Saved Option D:", OUT_DIR / "grainfield_option_4_granary_homestead.png")

