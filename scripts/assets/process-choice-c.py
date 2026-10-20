import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
TARGET_SIZE = 384

# ==========================================
# 1. PROCESS FARMLAND C (c-industrial/farm.png)
# ==========================================
farm_raw = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/c-industrial/farm.png").convert("RGBA")
farm_arr = np.array(farm_raw)

# Studio background color is around (215, 210, 206)
bg_col = np.array([215, 210, 206], dtype=float)
diff_farm = np.sqrt(np.sum((farm_arr[:, :, :3].astype(float) - bg_col)**2, axis=2))

# Create smooth alpha mask
alpha_farm = np.clip((diff_farm - 10) / 15 * 255, 0, 255).astype(np.uint8)

# Mask out tiny floating noise outside main diorama
# The diorama is roughly inside polygon / bbox
farm_trans = Image.fromarray(np.dstack([farm_arr[:, :, :3], alpha_farm]))

# Crop tightly around active diorama so it occupies maximum space
# Bounding box of diorama in 1254x1254:
farm_crop_box = (15, 80, 1238, 1230)
farm_trans_cropped = farm_trans.crop(farm_crop_box).resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)

# 1A. Full Square Version for Farmland C:
# Fill the background with agricultural earth/grass that matches the farm's soil perimeter
farm_soil_base = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (88, 76, 56, 255))
# Add subtle organic noise
base_arr = np.array(farm_soil_base)
np.random.seed(42)
noise = np.random.randint(-8, 8, base_arr[:, :, :3].shape)
base_arr[:, :, :3] = np.clip(base_arr[:, :, :3] + noise, 0, 255)
farm_soil_base = Image.fromarray(base_arr).filter(ImageFilter.GaussianBlur(1))

# Composite onto soil base
farm_full_square = Image.alpha_composite(farm_soil_base, farm_trans_cropped).convert("RGB")
farm_full_square.save(OUT_DIR / "grainfield_c_full_square.png")

# 1B. Transparent Version for Farmland C:
# Add ground contact shadow
farm_shadow = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
s_draw = ImageDraw.Draw(farm_shadow)
s_draw.polygon([
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.08),
    (TARGET_SIZE * 0.95, TARGET_SIZE * 0.45),
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.96),
    (TARGET_SIZE * 0.05, TARGET_SIZE * 0.55)
], fill=(20, 15, 10, 140))
farm_shadow = farm_shadow.filter(ImageFilter.GaussianBlur(12))

farm_trans_final = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
farm_trans_final.alpha_composite(farm_shadow)
farm_trans_final.alpha_composite(farm_trans_cropped)
farm_trans_final.save(OUT_DIR / "grainfield_c_transparent.png")

# ==========================================
# 2. PROCESS OILFIELD C (b-frontier/oilfield.png)
# ==========================================
oil_raw = Image.open(ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png").convert("RGBA")
oil_arr = np.array(oil_raw)

diff_oil = np.sqrt(np.sum((oil_arr[:, :, :3].astype(float) - bg_col)**2, axis=2))
alpha_oil = np.clip((diff_oil - 10) / 16 * 255, 0, 255).astype(np.uint8)

oil_trans = Image.fromarray(np.dstack([oil_arr[:, :, :3], alpha_oil]))
oil_crop_box = (25, 90, 1230, 1220)
oil_trans_cropped = oil_trans.crop(oil_crop_box).resize((TARGET_SIZE, TARGET_SIZE), Image.Resampling.LANCZOS)

# 2A. Full Square Version for Oilfield C:
# Fill the background with industrial gravel & oil dirt matching yard
oil_base = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (92, 80, 62, 255))
o_base_arr = np.array(oil_base)
noise_oil = np.random.randint(-10, 10, o_base_arr[:, :, :3].shape)
o_base_arr[:, :, :3] = np.clip(o_base_arr[:, :, :3] + noise_oil, 0, 255)
oil_base = Image.fromarray(o_base_arr).filter(ImageFilter.GaussianBlur(1))

oil_full_square = Image.alpha_composite(oil_base, oil_trans_cropped).convert("RGB")
oil_full_square.save(OUT_DIR / "oilfield_c_full_square.png")

# 2B. Transparent Version for Oilfield C:
oil_shadow = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
os_draw = ImageDraw.Draw(oil_shadow)
os_draw.polygon([
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.12),
    (TARGET_SIZE * 0.94, TARGET_SIZE * 0.48),
    (TARGET_SIZE * 0.5, TARGET_SIZE * 0.95),
    (TARGET_SIZE * 0.06, TARGET_SIZE * 0.52)
], fill=(20, 15, 10, 150))
oil_shadow = oil_shadow.filter(ImageFilter.GaussianBlur(14))

oil_trans_final = Image.new("RGBA", (TARGET_SIZE, TARGET_SIZE), (0, 0, 0, 0))
oil_trans_final.alpha_composite(oil_shadow)
oil_trans_final.alpha_composite(oil_trans_cropped)
oil_trans_final.save(OUT_DIR / "oilfield_c_transparent.png")

print("Processed both Farmland C and Oilfield C in Full Square and Transparent modes.")
